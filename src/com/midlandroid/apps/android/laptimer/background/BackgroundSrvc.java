package com.midlandroid.apps.android.laptimer.background;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StreamCorruptedException;
import java.text.NumberFormat;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;

import com.midlandroid.apps.android.laptimer.background.timers.SimpleCountDown;
import com.midlandroid.apps.android.laptimer.background.timers.SimpleCountUp;
import com.midlandroid.apps.android.laptimer.background.timers.TimerMode;
import com.midlandroid.apps.android.laptimer.background.timers.TimerMode.RunningState;
import com.midlandroid.apps.android.laptimer.background.timers.TimerUpdateServiceListener;
import com.midlandroid.apps.android.laptimer.background.timers.TimerUpdateUIListener;
import com.midlandroid.apps.android.laptimer.util.AppPreferences;
import com.midlandroid.apps.android.laptimer.util.ServiceCommand;
import com.midlandroid.apps.android.laptimer.util.TextUtil;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.util.Log;

public class BackgroundSrvc extends Service {
	private static final String LOG_TAG = BackgroundSrvc.class.getSimpleName();
	private static final String TIMER_STATE_FILENAME = "SavedTimerState.javaobject";

	private static final int TIMER_UPDATE_STEP_MILLS = 100;

	private NumberFormat numFormat;
	
    // Background service controls
	private TaskQueue bckgrndTasks;
	private AppPreferences appPrefs;
	private PowerManager.WakeLock wakeLock;
	private TimerUpdateUIListener uiListener;
	
	// Timer controls
	
	private TimerState state;
	
	// Timer Task and its container
	private Timer timer;


    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class LocalBinder extends Binder {
    	public BackgroundSrvc getService() {
            return BackgroundSrvc.this;
        }
    }
    

    // This is the object that receives interactions from clients.  See
    // RemoteService for a more complete example.
    private final IBinder mBinder = new LocalBinder();
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}
	
	
	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(LOG_TAG, "onCreate");
		
		// Initialize the number formatter
        numFormat = NumberFormat.getInstance();
        numFormat.setMinimumIntegerDigits(2);
        numFormat.setMaximumIntegerDigits(2);
        numFormat.setParseIntegerOnly(true);
		
		// Restore's the timer state from the app's local storage.
		_restoreState();
		
        // Create the background task queue
		bckgrndTasks = new TaskQueue();
		bckgrndTasks.start();
		
		// Create the app preferences handler
		appPrefs = new AppPreferences(this.getBaseContext());
		
		// Set timer mode
		// TODO replace this with user specified timer modes
		TimerMode mode = new SimpleCountUp(myMessenger);
		mode.setUpdateServiceListener(serviceListener);
		mode.setTimerName("");		
		state.pushToTimerModeStack(mode);
        
        // Create the background timer thread
        timer = new Timer(false);
        timer.schedule(timerTask, 0, TIMER_UPDATE_STEP_MILLS);
	}
	
	
	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		Log.d(LOG_TAG, "onStart");
	}
	
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.d(LOG_TAG, "onDestroy");
		
		// Save off the timer state.
		_saveState();
		
		// Make sure to clean up and stop the timer on exit
		_doStopTimer();
		
		// Stop timer and clear it
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
	}
	
	
    /**
     * Incoming messages from external sources.
     * @author Jason Del Ponte
     */
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case ServiceCommand.CMD_START_STOP_TIMER:
            	if (state.getRunningState() != RunningState.RUNNING) {
        			_doStartTimer();
            	} else {
            		_doStopTimer();
            	}
            	break;
            	
            case ServiceCommand.CMD_LAP_INCREMENT:
            	// ignore the lap increment if the timers not running.
            	if (state.getRunningState() == RunningState.RUNNING)
            		_doLapIncrement();
            	break;
            	
            case ServiceCommand.CMD_RESET_TIMER:
            	_doResetTimer();
            	break;
            	
            case ServiceCommand.CMD_REFRESH_MAIN_UI:
            	_doRefreshMainUI();
            	break;
            	
            case ServiceCommand.CMD_TIMER_FINISHED:
            	_currTimerFinished();
            	break;
            	
	        default:
	            super.handleMessage(msg);
	            break;
	        }
        }
    }
    private final IncomingHandler inHandler = new IncomingHandler();
    
    // Target we publish for clients to send messages to IncomingHandler.
    public final Messenger myMessenger = new Messenger(inHandler);
    
    
    /**
     * Loads the application's default shared preferences into memory
     */
    public void updateAppPreferences() {
    	appPrefs.loadPrefs();
    }


	/**
     * Returns the container object of the application's default shared preferences
     * @return
     */
    public AppPreferences getAppPreferences() {
    	return appPrefs;
    }
    
    
    /**
     * Returns the current timer state
     * @return current running state
     */
	public RunningState getTimerState() {
		return state.getRunningState();
	}
	
	
	/**
	 * Returns the name of the last timer mode used
	 * @return Timer Mode name
	 */
	public String getTimerModeName() {
		TimerMode mode = state.peekAtTimerModeStack();
		if (mode != null)
			return mode.getTimerName();
		else
			return "No Timer Selected";
	}
	
	
	/**
	 * Returns the time the timer was last started as.
	 * @return start time of the timer
	 */
	public long getTimerStartTime() {
		return state.getTimerStartTime();
	}
	
	
	/**
	 * Sets the Timer update UI listener object that can be used by each
	 * timer mode
	 * @param uiListener
	 */
	public void setUpdateUIListener(final TimerUpdateUIListener listener) {
		uiListener = listener;
	}
	
	
	/**
	 * Clears all timer modes' UI update listeners
	 */
	public void clearUpdateUIListener() {
		uiListener = null;
	}
	
	
	///////////////////////////////////////////////////
	// Private Methods
	///////////////////////////////////////////////////
	private TimerTask timerTask = new TimerTask() {
        private long totalRunTime = 0;
        @Override
        public void run() {
        	TimerState curState = state;
        	
            // Run the timer modes time update process
            TimerMode mode = curState.peekAtTimerModeStack();
            if (mode != null) {
                
                // Process the timer commands if there are any
                boolean doTimeUpdate = true;
                //boolean doScheduleNextUpdate = true;
                
                switch(curState.getTimerCommand()) {
                case ServiceCommand.CMD_LAP_INCREMENT:
                	curState.setTimerCommand(curState.getTimerCommandToRestore());
                    mode.procLapEvent();
                    break;
                    
                case ServiceCommand.CMD_REFRESH_MAIN_UI:
                	curState.setTimerCommand(curState.getTimerCommandToRestore());
                    
                    mode.procRefreshUI();
                    
                    // Don't update the timer while stopped if this is just
                    // a refresh of the screen.
                    if (curState.getTimerCommand() != ServiceCommand.CMD_PROC_TIMER_UPDATES)
                        doTimeUpdate = false;
                    break;
                    
                case ServiceCommand.CMD_PROC_TIMER_UPDATES:
                	curState.setRunningState(RunningState.RUNNING);
                    break;
                    
                case ServiceCommand.CMD_DONT_PROC_TIMER_UPDATES:
                    doTimeUpdate = false;
                    break;
                    
                case ServiceCommand.CMD_STOP_TIMER:
                    doTimeUpdate = false;
                    
                    // prevent the timer from doing any updates
                    curState.setTimerCommand(ServiceCommand.CMD_DONT_PROC_TIMER_UPDATES);
                    
                    // Update the timer state
                    curState.setRunningState(RunningState.STOPPED);
                    break;
                    
                case ServiceCommand.CMD_RESET_TIMER:
                    doTimeUpdate = false;
                    
                    mode.procResetTimer();
                    totalRunTime = 0; 
                    
                    // Reset the start timer offsets
                    curState.resetState();
                    
                    // Reset the UI
                    if (uiListener!=null) {
                        uiListener.resetUI();
                    }
                    break;
                }
                
                // Update the timer with the new time.
                if (doTimeUpdate) {
                    //Log.d("LOG_TAG timer update", "Doing timer update");
                    // get the time difference since last update
                    long currSysTime = System.currentTimeMillis();
                    
                    // Update the total runtime in case we were paused.
                    if (curState.getTimerStartOffset() != 0) {
                        // the total time should only be offset once per restart
                        totalRunTime += curState.getTimerStartOffset();
                        curState.setTimerStartOffset(0);
                    }
                    
                    // Calculate the new run time and current slice
                    long newRunTime = currSysTime - curState.getTimerStartTime();
                    long currTimeSlice = newRunTime - totalRunTime;
                    
                    // Do the time slice
                    mode.procTimerUpdate(currTimeSlice);
                    
                    // Save off the total run time
                    totalRunTime = newRunTime;
                }
            } else {
                // Nothing to do, but stop the timer
                _doStopTimer();
            }
        }
    };
    
    
	/**
	 * Creates the background timer using the Android 
	 * system message Handler
	 */
	private void _doStartTimer() {
		Log.d(LOG_TAG, "doStartTimer");
		
		TimerState curState = state;
		
		// Should a delay timer be used in addition to the normal timer
		if (appPrefs.getUseDelayTimer() && 
				(!curState.getWasDelayTimerAlreadyUsed() || appPrefs.getUseDelayTimerOnRestarts())) {

			// Create a special count down timer to be used as a delayed timer
			TimerMode mode = new SimpleCountDown(myMessenger, appPrefs.getTimerStartDelay());
			mode.setUpdateServiceListener(serviceListener);
			mode.setTimerName("Delay Count Down");
			
			// Add the timer mode to the list
			curState.pushToTimerModeStack(mode);
			
			curState.setWasDelayTimerAlreadyUsed(true);
		}
		
		// Find out what time we are starting the timers at and start them
		if (curState.getTimerStartTime() == 0)
			curState.setTimerStartTime(System.currentTimeMillis());
		else
			curState.setTimerStartOffset(System.currentTimeMillis() - curState.getTimerPausedAt());
		
        // Set the timer start mode
		curState.setTimerCommand(ServiceCommand.CMD_PROC_TIMER_UPDATES);

		// Grab the power manager wake lock if it's enabled
		if (appPrefs.getUseWakeLock()) {
			_grabWakeLock();
		}
	}
	
	
	/**
	 * Increment the timers lap
	 */
	private void _doLapIncrement() {
		Log.d(LOG_TAG, "doLapIncrement");
		
		TimerState curState = state;
		
		// Tell the timer a new lap event was received
		curState.setTimerCommandToRestore(curState.getTimerCommand());
		curState.setTimerCommand(ServiceCommand.CMD_LAP_INCREMENT);
	}
	
	
	/**
	 * Stops the background timer updates
	 */
	private void _doStopTimer() {
		Log.d(LOG_TAG, "doStopTimer");
		
		TimerState curState = state;
		
		// Save off the time the timer was stopped in case it is restarted
		curState.setTimerPausedAt(System.currentTimeMillis());
		
		// Tell the timer to stop processing updates.
		curState.setTimerCommand(ServiceCommand.CMD_STOP_TIMER);
		
		// Release the power manager wake lock if it was enabled
		if (appPrefs.getUseWakeLock()) {
			_releaseWakeLock();
		}
	}
	
	
	/**
	 * Reset the timer
	 */
	private void _doResetTimer() {
		Log.d(LOG_TAG, "doResetTimer");
		
		// Reset the timer state objects
		state.setTimerCommand(ServiceCommand.CMD_RESET_TIMER);
		
		// Remove the saved timer state file
		_destroySavedState();
		
		// Release the power manager wake lock if it was enabled
		if (appPrefs.getUseWakeLock()) {
			_releaseWakeLock();
		}
	}
	
	
	/**
	 * Refresh the main UI
	 */
	private void _doRefreshMainUI() {
		Log.d(LOG_TAG, "doRefreshMainUI");
		
		TimerState curState = state;
		
		// Using the current saved state refresh the UI
		serviceListener.setTimerHistory(state.getHistoryAsMultiLineString());
				
		// Tell the timer to refresh the UI
		curState.setTimerCommandToRestore(curState.getTimerCommand());
		curState.setTimerCommand(ServiceCommand.CMD_REFRESH_MAIN_UI);
	}
	
    
    /**
     * The current timer has finished and the next timer in the 
     * stack needs to be activated.
     */
    private void _currTimerFinished() {
    	TimerUpdateUIListener uiUpdate = uiListener;
    	
    	TimerState curState = state;
    	
    	TimerMode finMode = curState.popFromTimerModeStack();
    	TimerMode nexMode = curState.peekAtTimerModeStack();
    	// notify the user that the current timer has finished
    	if (uiUpdate != null) {
    		if (finMode != null)
    	    	curState.addItemToTopOfHistory("Finished at: ");
    		
    		if (nexMode != null)
    			curState.addItemToTopOfHistory("Started at: ");
    		
    		// Reset the lap data since they are timer specific
    		//uiUpdate.resetLaps();

    		serviceListener.setTimerHistory(curState.getHistoryAsMultiLineString());
    	}
    	
    	// Notify the user that the timer has finished
    	if (appPrefs.getUseAudioAlerts())
    		_soundAudioAlert();
	}
    

	/**
	 * Saves the current timer state to the app's local storage
	 */
	private void _saveState() {
		try {
			FileOutputStream fOS = openFileOutput(TIMER_STATE_FILENAME, Context.MODE_PRIVATE);
			ObjectOutputStream oOS = new ObjectOutputStream(fOS);
			oOS.writeObject(state);
		} catch (FileNotFoundException e) {
			Log.e(LOG_TAG, "Failed to save timer state", e);
		} catch (IOException e) {
			Log.e(LOG_TAG, "Failed to save timer state", e);
		}
	}
	
	
	/**
	 * Reads the save timer state from local storage
	 * and sets the timer's state values from it
	 */
	private void _restoreState() {
		try {
			FileInputStream fIS = openFileInput(TIMER_STATE_FILENAME);
			ObjectInputStream oIS = new ObjectInputStream(fIS);
			state = (TimerState)oIS.readObject();
		} catch (FileNotFoundException e) {
			Log.i(LOG_TAG, "No saved state found", e);
			
			// Create new instance of state since a saved
			// version does not exist.
			state = new TimerState();
			
		} catch (StreamCorruptedException e) {
			Log.e(LOG_TAG, "Failed to restore saved state", e);

			// Failed to load saved state
			state = new TimerState();
			
		} catch (IOException e) {
			Log.e(LOG_TAG, "Failed to restore saved state", e);

			// Failed to load saved state
			state = new TimerState();
			
		} catch (ClassNotFoundException e) {
			Log.e(LOG_TAG, "Failed to restore saved state", e);

			// Failed to load saved state
			state = new TimerState();
		}
	}
	
	
	/**
	 * Delete's the saved timer state information
	 * stored in the app's private storage.
	 */
	private void _destroySavedState() {
		deleteFile(TIMER_STATE_FILENAME);
	}
    
    
    /**
     * Create an audible alert notifying the user that the current timer has
     * been completed.
     */
    private void _soundAudioAlert() {
    	// TODO create audio alert
    }
	
	
	/**
	 * Grab the wake lock preventing the screen from timing out
	 * if the option is set
	 */
	private void _grabWakeLock() {
		if (wakeLock == null) {
			PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
			wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, LOG_TAG);
		}
		
		if (!wakeLock.isHeld())
			wakeLock.acquire();
	}
	
	
	/**
	 * Releases the previously grabbed wake lock
	 */
	private void _releaseWakeLock() {
		if (wakeLock != null && wakeLock.isHeld()) {
			wakeLock.release();
		}
	}
	
	private
	TimerUpdateServiceListener serviceListener = new TimerUpdateServiceListener() {
		@Override
		public void setCurrentTime(final long currTime) {
			if (uiListener != null)
				uiListener.setCurrentTime(currTime);
		}

		@Override
		public void setLapTime(final long lapTime) {
			if (uiListener != null)
				uiListener.setLapTime(lapTime);
		}

		@Override
		public void setLapCount(final int count) {
			if (uiListener != null)
				uiListener.setLapCount(count);
		}

		@Override
		public void doLapCountIncrement(final long currTime, final long lapTime, final int lapCount) {
			setLapCount(lapCount);
			
			state.addItemToTopOfHistory(new String("Lap "+lapCount+": "+TextUtil.formatDateToString(lapTime, numFormat)+
					" - "+TextUtil.formatDateToString(currTime, numFormat)));
			
			setTimerHistory(state.getHistoryAsMultiLineString());
		}

		@Override
		public void setTimerHistory(final String history) {
			if (uiListener != null)
				uiListener.setTimerHistory(history);
		}

		@Override
		public void resetUI() {
			if (uiListener != null)
				uiListener.resetUI();
		}
	};
	
}