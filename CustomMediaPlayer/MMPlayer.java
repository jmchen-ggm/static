package com.tencent.mm.plugin.music.model.player;

import android.annotation.TargetApi;
import android.media.*;
import android.os.Process;
import com.tencent.mm.platformtools.Util;
import com.tencent.mm.sdk.platformtools.Log;
import com.tencent.mm.sdk.thread.ThreadPool;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by jiaminchen on 16/6/12.
 */
@TargetApi(16)
public class MMPlayer extends BasePlayer {

    private final static String TAG = "MicroMsg.Music.MMPlayer";

    private MediaExtractor extractor;
    private MediaCodec codec;
    private AudioTrack audioTrack;

    private String sourcePath;

    private boolean stop = true;

    private String mime = null;
    private int sampleRate = 0;
    private int channels = 0;
    private long presentationTimeUs = 0;
    private long duration = 0;

    @Override
    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    private Runnable playRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "starting...");
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

            if (Util.isNullOrNil(sourcePath)) {
                Log.e(TAG, "source path is null");
                onError(false);
                return;
            }

            extractor = new MediaExtractor();
            try {
                extractor.setDataSource(sourcePath);
            } catch (Exception e) {
                Log.printErrStackTrace(TAG, e, "set extractor data source");
                onError(true);
                return;
            }

            MediaFormat format = null;
            try {
                int tractCount = extractor.getTrackCount();
                for (int i = 0; i < tractCount; i++) {
                    MediaFormat tempFormat = extractor.getTrackFormat(i);
                    mime = tempFormat.getString(MediaFormat.KEY_MIME);
                    // find the audio track
                    if (!Util.isNullOrNil(mime) && mime.startsWith("audio/")) {
                        format = tempFormat;
                        break;
                    }
                }
                if (format == null) {
                    Log.e(TAG, "format is null");
                    onError(true);
                    releaseMediaExtractor();
                    return;
                }
                // this sample rate and channels is just for tips, real sample rate and channels is in the codec
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                // if duration is 0, we are probably playing a live stream
                duration = format.getLong(MediaFormat.KEY_DURATION);
            } catch (Exception e) {
                Log.printErrStackTrace(TAG, e, "get media format from media extractor");
            }
            // create the actual decoder, using the mime to select
            try {
                codec = MediaCodec.createDecoderByType(mime);
            } catch (IOException e) {
                Log.printErrStackTrace(TAG, e, "createDecoderByType");
                onError(true);
                releaseMediaExtractor();
                releaseMediaCodec();
                return;
            }

            codec.configure(format, null, null, 0);
            codec.start();

            ByteBuffer[] codecInputBuffers  = codec.getInputBuffers();
            ByteBuffer[] codecOutputBuffers = codec.getOutputBuffers();
            Log.i(TAG, "Track info: extractorFormat: %s mime: %s sampleRate: %s channels: %s duration: %s",
                format, mime, sampleRate, channels, duration);
            extractor.selectTrack(0);
            // start decoding
            final long kTimeOutUs = 1000;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            int noOutputCounter = 0;
            int noOutputCounterLimit = 10;

            state.set(PlayerStates.PLAYING);
            onStart();
            try {
                while (!sawOutputEOS && noOutputCounter < noOutputCounterLimit) {
                    // pause implementation
                    waitPlay();
                    if (!isInPlayState()) {
                        break;
                    }
                    noOutputCounter++;
                    // read a buffer before feeding it to the decoder
                    if (!sawInputEOS) {
                        int inputBufIndex = codec.dequeueInputBuffer(kTimeOutUs);
                        if (inputBufIndex >= 0) {
                            ByteBuffer dstBuf = codecInputBuffers[inputBufIndex];
                            int sampleSize = extractor.readSampleData(dstBuf, 0);
                            if (sampleSize < 0) {
                                Log.d(TAG, "saw input EOS. Stopping playback");
                                sawInputEOS = true;
                                sampleSize = 0;
                            } else {
                                presentationTimeUs = extractor.getSampleTime();
                                final int percent = (duration == 0) ? 0 : (int) (100 * presentationTimeUs / duration);
                                onPlayUpdate(percent);
                            }
                            codec.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTimeUs, sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                            if (!sawInputEOS) {
                                extractor.advance();
                            }
                        } else {
                            Log.e(TAG, "inputBufIndex " + inputBufIndex);
                        }
                    } // !sawInputEOS
                    // decode to PCM and push it to the AudioTrack player
                    int res = codec.dequeueOutputBuffer(info, kTimeOutUs);
                    if (res >= 0) {
                        if (info.size > 0) {
                            noOutputCounter = 0;
                        }
                        int outputBufIndex = res;
                        ByteBuffer buf = codecOutputBuffers[outputBufIndex];
                        final byte[] chunk = new byte[info.size];
                        buf.get(chunk);
                        buf.clear();
                        if (chunk.length > 0) {
                            if (audioTrack == null) {
                                if (!createAudioTrack()) {
                                    Log.e(TAG, "audio track not initialized");
                                    onError(true);
                                    return;
                                }
                                // start play
                                audioTrack.play();
                            }
                            audioTrack.write(chunk, 0, chunk.length);
                        }
                        codec.releaseOutputBuffer(outputBufIndex, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.d(TAG, "saw output EOS.");
                            sawOutputEOS = true;
                        }
                    } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        codecOutputBuffers = codec.getOutputBuffers();
                        Log.i(TAG, "output buffers have changed.");
                    } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outputFormat = codec.getOutputFormat();
                        Log.i(TAG, "output format has changed to " + outputFormat);
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        releaseAudioTrack();
                    } else {
                        Log.i(TAG, "dequeueOutputBuffer returned " + res);
                    }
                }
                boolean isComplete = duration / 1000 - presentationTimeUs / 1000  < 2000;
                if (noOutputCounter >= noOutputCounterLimit) {
                    onError(true);
                } else {
                    onStop(isComplete);
                }
            } catch (Exception e) {
                Log.printErrStackTrace(TAG, e, "error");
                onError(true);
            } finally {
                releaseMediaExtractor();
                releaseMediaCodec();
                releaseAudioTrack();
                // clear source and the other globals
                sourcePath = null;
                mime = null;
                sampleRate = 0;
                channels = 0;
                presentationTimeUs = 0;
                duration = 0;
            }
            Log.i(TAG, "stopping...");
        }
    };

    private boolean createAudioTrack() {
        Log.i(TAG, "createAudioTrack");
        int channelConfiguration = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int miniBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfiguration, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,channelConfiguration ,
            AudioFormat.ENCODING_PCM_16BIT, miniBufferSize, AudioTrack.MODE_STREAM);
        if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "audio track not initialized");
            return false;
        } else {
            return true;
        }
    }

    private void releaseMediaExtractor() {
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }
    }

    private void releaseMediaCodec() {
        if(codec != null) {
            codec.stop();
            codec.release();
            codec = null;
        }
    }

    private void releaseAudioTrack() {
        if (audioTrack != null) {
            audioTrack.flush();
            audioTrack.release();
            audioTrack = null;
        }
    }

    @Override
    public int getCurrentPos() {
        return (int) (presentationTimeUs / 1000);
    }

    @Override
    public int getDuration() {
        return (int) (duration / 1000);
    }

    @Override
    public void play() {
        Log.i(TAG, "play");
        if (!isInPlayState()) {
            stop = false;
            ThreadPool.post(playRunnable, "music_player");
        } else if (isInPlayState() && state.isReadyToPlay()) {
            state.set(PlayerStates.PLAYING);
            syncNotify();
        }
    }

    /**
     * Call notify to control the PAUSE (waiting) state, when the state is changed
     */
    public synchronized void syncNotify() {
        Log.i(TAG, "sync notify");
        try {
            notify();
        } catch (Exception e) {
        }
    }

    @Override
    public void stop() {
        Log.i(TAG, "stop");
        stop = true;
        if (state.isReadyToPlay()) {
            syncNotify();
        }
    }

    @Override
    public void pause() {
        Log.i(TAG, "pause");
        if (!isInPlayState()) {
            return;
        }
        state.set(PlayerStates.READY_TO_PLAY);
    }

    @Override
    public void seek(long pos) {
        extractor.seekTo(pos * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
    }

    public void seek(int percent) {
        long pos = percent * getDuration() / 100;
        seek(pos);
    }

    /**
     * A pause mechanism that would block current thread when pause flag is set (READY_TO_PLAY)
     */
    public synchronized void waitPlay(){
        while(isInPlayState() && state.isReadyToPlay()) {
            try {
                Log.i(TAG, "wait play");
                wait();
            } catch (InterruptedException e) {
            }
        }
    }

    @Override
    public boolean isPlaying() {
        return this.state.isPlaying();
    }

    @Override
    public boolean isInPlayState() {
        return !stop;
    }

    @Override
    protected void onError(boolean needRetry) {
        state.set(PlayerStates.STOPPED);
        stop = true;
        super.onError(needRetry);
    }

    @Override
    protected void onStop(boolean isComplete) {
        state.set(PlayerStates.STOPPED);
        stop = true;
        super.onStop(isComplete);
    }
}
