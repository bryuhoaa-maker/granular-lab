package com.granularlab;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Декод сжатых форматов системным MediaCodec (API 16) в моно float 44100. */
public class Decoder {

    public static float[] decode(String path) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(path);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) throw new Exception("нет аудио-дорожки");
            ex.selectTrack(track);

            int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();

            ByteBuffer[] inBufs = dec.getInputBuffers();
            ByteBuffer[] outBufs = dec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            float[] acc = new float[Engine.SR * 8];
            int accN = 0;
            boolean inDone = false, outDone = false;
            int guard = 0;
            int cap = 60 * sr;
            long got = 0;

            while (!outDone && guard++ < 40000) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer b = inBufs[ii];
                        b.clear();
                        int size = ex.readSampleData(b, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outBufs = dec.getOutputBuffers();
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = dec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (nf.containsKey("pcm-encoding") && nf.getInteger("pcm-encoding") != 2) {
                        throw new Exception("декодер вернул не 16-bit PCM");
                    }
                    outBufs = dec.getOutputBuffers();
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = outBufs[oi];
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int frames = sb.remaining() / ch;
                        for (int i = 0; i < frames && got < cap; i++) {
                            float a = 0;
                            for (int c = 0; c < ch; c++) a += sb.get() / 32768f;
                            if (accN >= acc.length) {
                                float[] t = new float[acc.length * 2];
                                System.arraycopy(acc, 0, t, 0, accN);
                                acc = t;
                            }
                            acc[accN++] = a / ch;
                            got++;
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            if (accN < 64) throw new Exception("декодер не дал звука");
            float[] out = new float[accN];
            System.arraycopy(acc, 0, out, 0, accN);
            if (sr != Engine.SR) out = Wav.resample(out, sr, Engine.SR);
            return out;
        } finally {
            if (dec != null) {
                try { dec.stop(); } catch (Exception ignored) { }
                try { dec.release(); } catch (Exception ignored) { }
            }
            ex.release();
        }
    }
}
