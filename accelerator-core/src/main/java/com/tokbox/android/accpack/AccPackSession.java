package com.tokbox.android.accpack;

import android.content.Context;
import android.util.Log;

import com.opentok.android.Connection;
import com.opentok.android.OpentokError;
import com.opentok.android.Session;
import com.opentok.android.Stream;
import com.tokbox.android.otsdkwrapper.signal.SignalInfo;
import com.tokbox.android.otsdkwrapper.signal.SignalProcessorThread;
import com.tokbox.android.otsdkwrapper.signal.SignalProtocol;
import com.tokbox.android.otsdkwrapper.utils.Callback;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;

public class AccPackSession extends Session {

    private final String LOG_TAG = this.getClass().getSimpleName();

    private ArrayList<SessionListener> mSessionListeners = new ArrayList<>();
    private ArrayList<ConnectionListener> mConnectionsListeners = new ArrayList<>();
    private ArrayList<ArchiveListener> mArchiveListeners = new ArrayList<>();
    private ArrayList<StreamPropertiesListener> mStreamPropertiesListeners = new ArrayList<>();
    private ArrayList<ReconnectionListener> mReconnectionListeners = new ArrayList<>();
    private Hashtable<String, ArrayList<com.tokbox.android.otsdkwrapper.listeners.SignalListener>> mSignalListeners = new Hashtable<String, ArrayList<com.tokbox.android.otsdkwrapper.listeners.SignalListener>>();;

    //signal protocol
    private SignalProtocol mInputSignalProtocol;
    private SignalProtocol mOutputSignalProtocol;
    private SignalProcessorThread mInputSignalProcessor;
    private SignalProcessorThread mOutputSignalProcessor;

    public AccPackSession(Context context, String apiKey, String sessionId) {
        super(context, apiKey, sessionId);
    }

    public void addSignalListener(String signalName, com.tokbox.android.otsdkwrapper.listeners.SignalListener listener) {
        Log.d(LOG_TAG, "Adding Signal Listener for: " + signalName);
        ArrayList<com.tokbox.android.otsdkwrapper.listeners.SignalListener> perNameListeners = mSignalListeners.get(signalName);
        if (perNameListeners == null) {
            perNameListeners = new ArrayList<com.tokbox.android.otsdkwrapper.listeners.SignalListener>();
            mSignalListeners.put(signalName, perNameListeners);
        }
        if (perNameListeners.indexOf(listener) == -1) {
            Log.d(LOG_TAG, "Signal listener for: " + signalName + " is new!");
            perNameListeners.add(listener);
        }
    }

    public void removeSignalListener(com.tokbox.android.otsdkwrapper.listeners.SignalListener listener) {
        Enumeration<String> signalNames = mSignalListeners.keys();
        while (signalNames.hasMoreElements()) {
            String signalName = signalNames.nextElement();
            Log.d(LOG_TAG, "removeSignal(" + listener.toString() + ") for " + signalName);
            removeSignalListener(signalName, listener);
        }
    }

    public void removeSignalListener(String signalName, com.tokbox.android.otsdkwrapper.listeners.SignalListener listener) {
        ArrayList<com.tokbox.android.otsdkwrapper.listeners.SignalListener> perNameListeners = mSignalListeners.get(signalName);
        if (perNameListeners == null) {
            return;
        }
        perNameListeners.remove(listener);
        if (perNameListeners.size() == 0) {
            mSignalListeners.remove(signalName);
        }
    }

    public void sendSignal(SignalInfo signalInfo, Connection connection) {
        if (mOutputSignalProtocol != null) {
            mOutputSignalProtocol.write(signalInfo);
        } else {
            if ( connection != null )
                internalSendSignal(signalInfo, connection);
            else
                internalSendSignal(signalInfo, null);
        }
    }

    public void internalSendSignal(SignalInfo signalInfo, Connection connection) {
        Log.d(LOG_TAG, "internalSendSignal: " + signalInfo.mSignalName);
        if (connection == null) {
            sendSignal(signalInfo.mSignalName, (String) signalInfo.mData);
        } else {
            sendSignal(signalInfo.mSignalName, (String) signalInfo.mData,
                    connection);
        }
    }

    public void cleanUpSignals() {
        setInputSignalProtocol(null);
        setOutputSignalProtocol(null);
    }

    public Session.SignalListener getSignalListener() {
        return mSignalListener;
    }

    private void dispatchSignal(ArrayList<com.tokbox.android.otsdkwrapper.listeners.SignalListener> listeners, final SignalInfo signalInfo,
                                boolean global) {
        if (listeners != null) {
            Iterator<com.tokbox.android.otsdkwrapper.listeners.SignalListener> listenerIterator = listeners.iterator();
            while (listenerIterator.hasNext()) {
                Log.d(LOG_TAG, "Starting thread to process: " + signalInfo.mSignalName);
                final com.tokbox.android.otsdkwrapper.listeners.SignalListener listener = listenerIterator.next();
                new Thread() {
                    @Override
                    public void run() {
                        Log.d(LOG_TAG, "Dispatching signal: " + signalInfo.mSignalName + " on thread: " +
                                this.getId());
                        listener.onSignalReceived(signalInfo, AccPackSession.this.getConnection().getConnectionId().equals(signalInfo.mSrcConnId));
                    }
                }.start();
            }
        } else {
            Log.d(LOG_TAG, "dispatchSignal: No " + (global ? "global " : "") +
                    "listeners registered for: " + signalInfo.mSignalName);
        }
    }

    /**
     * Sets a input signal processor. The input processor will process all the signals coming from
     * the wire. The SignalListeners will be invoked only on processed signals. That allows you to
     * easily implement and enforce a connection wide protocol for all sent and received signals.
     * @param inputProtocol The input protocol you want to enforce. Pass null if you wish to receive
     *                      raw signals.
     */
    public synchronized void setInputSignalProtocol(SignalProtocol inputProtocol) {
        mInputSignalProtocol = inputProtocol;
        mInputSignalProcessor =
                refreshSignalProcessor(mInputSignalProcessor, mInputSignalProtocol, mDispatchSignal);
    }

    /**
     * Sets a output signal protocol. The output protocol will process all the signals going to
     * the wire. A Signal will be sent using Opentok only after it has been processed by the protocol.
     * That allows you to easily implement and enforce a connection wide protocol for all sent and
     * received signals.
     * @param outputProtocol
     */
    public synchronized void setOutputSignalProtocol(SignalProtocol outputProtocol) {
        mOutputSignalProtocol = outputProtocol;
        mOutputSignalProcessor =
                refreshSignalProcessor(mOutputSignalProcessor, mOutputSignalProtocol, mInternalSendSignal);
    }

    private void dispatchSignal(final SignalInfo signalInfo) {
        Log.d(LOG_TAG, "Dispatching signal: " + signalInfo.mSignalName + " with: " + signalInfo.mData);
        dispatchSignal(mSignalListeners.get("*"), signalInfo, true);
        dispatchSignal(mSignalListeners.get(signalInfo.mSignalName), signalInfo, false);
    }

    private Callback<SignalInfo> mInternalSendSignal = new Callback<SignalInfo>() {
        @Override
        public void run(SignalInfo signalInfo) {
            internalSendSignal(signalInfo, null); //TODO-MARINAS: Fix dst connection is not null
        }
    };

    private Callback<SignalInfo> mDispatchSignal = new Callback<SignalInfo>() {
        @Override
        public void run(SignalInfo signalInfo) {
            dispatchSignal(signalInfo);
        }
    };

    private SignalProcessorThread refreshSignalProcessor(SignalProcessorThread currentProcessor,
                                                         SignalProtocol signalProtocol,
                                                         Callback<SignalInfo> cb) {
        if (currentProcessor != null) {
            return currentProcessor.switchPipe(signalProtocol);
        } else  {
            return new SignalProcessorThread(signalProtocol, cb);
        }
    }


    private Session.SignalListener mSignalListener = new Session.SignalListener() {
        @Override
        public void onSignalReceived(Session session, String signalName, String data,
                                     Connection connection) {
            String connId = connection != null ? connection.getConnectionId() : null;
            SignalInfo inputSignal = new SignalInfo(connId, AccPackSession.this.getConnection().getConnectionId(), signalName, data);
            if (mInputSignalProtocol != null) {
                mInputSignalProtocol.write(inputSignal);
            } else {
                dispatchSignal(inputSignal);
            }
        }
    };

    @Override
    public void setSessionListener(SessionListener listener) {
        super.setSessionListener(listener);
        mSessionListeners.add(listener);
    }

    @Override
    public void setConnectionListener(ConnectionListener listener) {
        super.setConnectionListener(listener);
        mConnectionsListeners.add(listener);
    }

    @Override
    public void setStreamPropertiesListener(StreamPropertiesListener listener) {
        super.setStreamPropertiesListener(listener);
        mStreamPropertiesListeners.add(listener);
    }

    @Override
    public void setArchiveListener(ArchiveListener listener) {
        super.setArchiveListener(listener);
        mArchiveListeners.add(listener);
    }

    @Override
    public void setReconnectionListener(ReconnectionListener listener) {
        super.setReconnectionListener(listener);
        mReconnectionListeners.add(listener);
    }

    @Override
    protected void onConnected() {
        for (SessionListener l : mSessionListeners) {
            l.onConnected(this);
        }
    }

    @Override
    protected void onReconnecting() {
        for (ReconnectionListener l : mReconnectionListeners) {
            l.onReconnecting(this);
        }
    }

    @Override
    protected void onReconnected() {
        for (ReconnectionListener l : mReconnectionListeners) {
            l.onReconnected(this);
        }
    }

    @Override
    protected void onDisconnected() {
        for (SessionListener l : mSessionListeners) {
            l.onDisconnected(this);
        }
    }

    @Override
    protected void onError(OpentokError error) {
        for (SessionListener l : mSessionListeners) {
            l.onError(this, error);
        }
    }

    @Override
    protected void onStreamReceived(Stream stream) {
        for (SessionListener l : mSessionListeners) {
            l.onStreamReceived(this, stream);
        }
    }

    @Override
    protected void onStreamDropped(Stream stream) {
        for (SessionListener l : mSessionListeners) {
            l.onStreamDropped(this, stream);
        }
    }

    @Override
    protected void onConnectionCreated(Connection connection) {
        for (ConnectionListener l : mConnectionsListeners) {
            l.onConnectionCreated(this, connection);
        }
    }

    @Override
    protected void onConnectionDestroyed(Connection connection) {
        for (ConnectionListener l : mConnectionsListeners) {
            l.onConnectionDestroyed(this, connection);
        }
    }

    @Override
    protected void onStreamHasAudioChanged(Stream stream, int hasAudio) {
        for (StreamPropertiesListener l : mStreamPropertiesListeners) {
            l.onStreamHasAudioChanged(this, stream, (hasAudio != 0));
        }
    }

    @Override
    protected void onStreamHasVideoChanged(Stream stream, int hasVideo) {
        for (StreamPropertiesListener l : mStreamPropertiesListeners) {
            l.onStreamHasVideoChanged(this, stream, (hasVideo != 0));
        }
    }

    @Override
    protected void onStreamVideoDimensionsChanged(Stream stream, int width, int height) {
        for (StreamPropertiesListener l : mStreamPropertiesListeners) {
            l.onStreamVideoDimensionsChanged(this, stream, width, height);
        }
    }

    @Override
    protected void onStreamVideoTypeChanged(Stream stream, Stream.StreamVideoType videoType) {
        for (StreamPropertiesListener l : mStreamPropertiesListeners) {
            l.onStreamVideoTypeChanged(this, stream, videoType);
        }
    }

    @Override
    protected void onArchiveStarted(String id, String name) {
        for (ArchiveListener l : mArchiveListeners) {
            l.onArchiveStarted(this, id, name);
        }
    }

    @Override
    protected void onArchiveStopped(String id) {
        for (ArchiveListener l : mArchiveListeners) {
            l.onArchiveStopped(this, id);
        }
    }
}