package com.code.aaron.micstream;

import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import android.annotation.TargetApi;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/** MicStreamPlugin
 *  In reference to flutters official sensors plugin
 *  and the example of the streams_channel (v0.2.2) plugin
 */

@TargetApi(16)  // Should be unnecessary, but isn't // fix build.gradle...?
public class MicStreamPlugin implements FlutterPlugin, EventChannel.StreamHandler, MethodCallHandler {
    private static final String MICROPHONE_CHANNEL_NAME = "aaron.code.com/mic_stream";
    private static final String MICROPHONE_METHOD_CHANNEL_NAME = "aaron.code.com/mic_stream_method_channel";

    /// New way of registering plugin
    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        registerWith(binding.getBinaryMessenger());
    }

    /// Cleanup after connection loss to flutter
    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        onCancel(null);
    }

    /// Deprecated way of registering plugin
    public void registerWith(Registrar registrar) {
        registerWith(registrar.messenger());
    }

    private void registerWith(BinaryMessenger messenger) {
        final EventChannel microphone = new EventChannel(messenger, MICROPHONE_CHANNEL_NAME);
        microphone.setStreamHandler(this);
        MethodChannel methodChannel = new MethodChannel(messenger, MICROPHONE_METHOD_CHANNEL_NAME);
        methodChannel.setMethodCallHandler(this);
    }

    private EventChannel.EventSink eventSink;

    // Audio recorder + initial values
    private static volatile AudioRecord recorder = null;
    short threshold=5000;
    private int SILENCE_DEGREE = 15;

    private int AUDIO_SOURCE = MediaRecorder.AudioSource.DEFAULT;
    private int SAMPLE_RATE = 16000;
    private double AUDIO_LEVEL = 0.1;
    private int PAUSE_INTERVAL = 40;
    private int actualSampleRate;
    private int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_8BIT;
    private int actualBitDepth;
    private int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

    // Runnable management
    private volatile boolean record = false;
    private volatile boolean isRecording = false;

    // Method channel handlers to get sample rate / bit-depth
    @Override
    public void onMethodCall(MethodCall call, Result result) {
        switch (call.method) {
            case "getSampleRate":
                result.success((double)this.actualSampleRate); // cast to double just for compatibility with the iOS version
                break;
            case "getBitDepth":
                result.success(this.actualBitDepth);
                break;
            case "getBufferSize":
                result.success(this.BUFFER_SIZE);
                break;
            case "getAudioLevel":
                result.success((double)this.AUDIO_LEVEL);
                break;
            case "getPauseInterval":
                result.success(this.PAUSE_INTERVAL);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void initRecorder () {
        // Try to initialize and start the recorder
        recorder = new AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            eventSink.error("-1", "PlatformError", null);
            return;
        }

        recorder.startRecording();
    }

    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) initRecorder();
            isRecording = true;

            actualSampleRate = recorder.getSampleRate();
            actualBitDepth = (recorder.getAudioFormat() == AudioFormat.ENCODING_PCM_8BIT ? 8 : 16);

            // Wait until recorder is initialised
            while (recorder == null || recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING);
            byte[] data = new byte[BUFFER_SIZE];
            short[] voice = new short[data.length / 2];
            int pauseTimed = 0;
            // Repeatedly push audio samples to stream
            while (record) {
//                recorder.read(voice, 0, BUFFER_SIZE);

                try {
                    recorder.read(data, 0, BUFFER_SIZE);
                    ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(voice);
                    // int foundPeak = searchThreshold(voice,threshold);

                    double rms = 0;
                    for (int i = 0; i < voice.length; i++) {
                        double normal = voice[i] / 32768f;
                        rms += normal * normal;
                    }
                    rms = Math.sqrt(rms / voice.length);
                    System.out.println("Listening, rms is " + rms);
                    if (rms <= 0.075) {
                        if (pauseTimed >= 40) {
                            System.out.println("Stopped Recording");
                            eventSink.success(new byte[0]);
                        } else {
                            pauseTimed++;
                            System.out.println("Pause counter " + pauseTimed);
                            eventSink.success(data);
                        }
                        // pause = true;
                    } else {
                        pauseTimed = 0;
                        eventSink.success(data);
                    }
                    // if (foundPeak == -1) {
                    //     if (silenceDegree <= SILENCE_DEGREE) {
                    //         silenceDegree++;
                    //     }
                    // } else {
                    //     silenceDegree = 0;
                    // }
                    // if (silenceDegree < SILENCE_DEGREE) {
                    //     eventSink.success(data);
                    // }

                } catch (Exception e) {
                    System.out.println("mic_stream: " + Arrays.hashCode(data) + " is not valid!");
                    eventSink.error("-1", "Invalid Data", e);
                }
            }
            isRecording = false;
        }
    };

    int searchThreshold(byte[]arr,short thr){
        int peakIndex;
        int arrLen=arr.length;
        for (peakIndex=0;peakIndex<arrLen;peakIndex++){
            if ((arr[peakIndex]>=thr) || (arr[peakIndex]<=-thr)){

                return peakIndex;
            }
        }
        return -1; //not found
    }


    /// Bug fix by https://github.com/Lokhozt
    /// following https://github.com/flutter/flutter/issues/34993
    private static class MainThreadEventSink implements EventChannel.EventSink {
        private EventChannel.EventSink eventSink;
        private Handler handler;

        MainThreadEventSink(EventChannel.EventSink eventSink) {
          this.eventSink = eventSink;
          handler = new Handler(Looper.getMainLooper());
        }

        @Override
        public void success(final Object o) {
          handler.post(new Runnable() {
            @Override
            public void run() {
              eventSink.success(o);
            }
          });
        }

        @Override
        public void error(final String s, final String s1, final Object o) {
          handler.post(new Runnable() {
            @Override
            public void run() {
              eventSink.error(s, s1, o);
            }
          });
        }

        @Override
        public void endOfStream() {
          handler.post(new Runnable() {
            @Override
            public void run() {
              eventSink.endOfStream();
            }
          });
        }
    }
    /// End

    @Override
    public void onListen(Object args, final EventChannel.EventSink eventSink) {
        if (isRecording) return;

        ArrayList<Integer> config = (ArrayList<Integer>) args;

        // Set parameters, if available
        switch(config.size()) {
            case 6:
                PAUSE_INTERVAL = config.get(5)
            case 5:
                AUDIO_LEVEL = config.get(4);
            case 4:
                AUDIO_FORMAT = config.get(3);
            case 3:
                CHANNEL_CONFIG = config.get(2);
            case 2:
                SAMPLE_RATE = config.get(1);
            case 1:
                AUDIO_SOURCE = config.get(0);
            default:
                try {
                    BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
                } catch (Exception e) {
                    eventSink.error("-3", "Invalid AudioRecord parameters", e);
                }
        }
        
        if(AUDIO_FORMAT != AudioFormat.ENCODING_PCM_8BIT && AUDIO_FORMAT != AudioFormat.ENCODING_PCM_16BIT) {
            eventSink.error("-3", "Invalid Audio Format specified", null);
            return;
        }

        this.eventSink = new MainThreadEventSink(eventSink);

        // Start runnable
        record = true;
        new Thread(runnable).start();
    }

    @Override
    public void onCancel(Object o) {
        // Stop runnable
        record = false;
        while (isRecording);
        if(recorder != null) {
            // Stop and reset audio recorder
            recorder.stop();
            recorder.release();
        }
        recorder = null;
    }
}