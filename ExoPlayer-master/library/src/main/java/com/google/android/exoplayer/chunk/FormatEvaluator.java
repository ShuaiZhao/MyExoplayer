/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.chunk;

import android.util.Log;

import com.google.android.exoplayer.upstream.BandwidthMeter;

import java.util.List;
import java.util.Random;

import static android.R.attr.format;
import static android.R.string.no;

/**
 * Selects from a number of available formats during playback.
 */
public interface FormatEvaluator {

    /**
     * Enables the evaluator.
     */
    void enable();

    /**
     * Disables the evaluator.
     */
    void disable();

    /**
     * Update the supplied evaluation.
     * <p>
     * When the method is invoked, {@code evaluation} will contain the currently selected
     * format (null for the first evaluation), the most recent trigger (TRIGGER_INITIAL for the
     * first evaluation) and the current queue size. The implementation should update these
     * fields as necessary.
     * <p>
     * The trigger should be considered "sticky" for as long as a given representation is selected,
     * and so should only be changed if the representation is also changed.
     *
     * @param queue              A read only representation of the currently buffered {@link MediaChunk}s.
     * @param playbackPositionUs The current playback position.
     * @param formats            The formats from which to select, ordered by decreasing bandwidth.
     * @param evaluation         The evaluation.
     */
    // TODO: Pass more useful information into this method, and finalize the interface.
    void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs, Format[] formats,
                  Evaluation evaluation);

    /**
     * A format evaluation.
     */
    public static final class Evaluation {

        /**
         * The desired size of the queue.
         */
        public int queueSize;

        /**
         * The sticky reason for the format selection.
         */
        public int trigger;

        /**
         * The selected format.
         */
        public Format format;

        public Evaluation() {
            trigger = Chunk.TRIGGER_INITIAL;
        }

    }

    /**
     * Always selects the first format.
     */
    public static final class FixedEvaluator implements FormatEvaluator {

        @Override
        public void enable() {
            // Do nothing.
        }

        @Override
        public void disable() {
            // Do nothing.
        }

        @Override
        public void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs,
                             Format[] formats, Evaluation evaluation) {
            evaluation.format = formats[0];
        }

    }

    /**
     * Selects randomly between the available formats.
     */
    public static final class RandomEvaluator implements FormatEvaluator {

        private final Random random;

        public RandomEvaluator() {
            this.random = new Random();
        }

        /**
         * @param seed A seed for the underlying random number generator.
         */
        public RandomEvaluator(int seed) {
            this.random = new Random(seed);
        }

        @Override
        public void enable() {
            // Do nothing.
        }

        @Override
        public void disable() {
            // Do nothing.
        }

        @Override
        public void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs,
                             Format[] formats, Evaluation evaluation) {
            Format newFormat = formats[random.nextInt(formats.length)];
            if (evaluation.format != null && !evaluation.format.equals(newFormat)) {
                evaluation.trigger = Chunk.TRIGGER_ADAPTIVE;
            }
            evaluation.format = newFormat;
        }

    }

    /**
     * An adaptive evaluator for video formats, which attempts to select the best quality possible
     * given the current network conditions and state of the buffer.
     * <p>
     * This implementation should be used for video only, and should not be used for audio. It is a
     * reference implementation only. It is recommended that application developers implement their
     * own adaptive evaluator to more precisely suit their use case.
     */
    public static final class AdaptiveEvaluator implements FormatEvaluator {

        public static final int DEFAULT_MAX_INITIAL_BITRATE = 800000;  //bps, 800Kbps
        public static final int DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS = 10000; //ms, 1s
        public static final int DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS = 25000; //ms, 2.5s
        public static final int DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 25000; //ms, 2.5s

        // DEFAULT_BANDWIDTH_FRACTION: What percentage of the current network bandwidth you can use
        //public static final float DEFAULT_BANDWIDTH_FRACTION = 0.75f;
        public static final float DEFAULT_BANDWIDTH_FRACTION = 0.1f;

        //added by shuai
        private static final String TAG = "FormatEvaluator";

        private final BandwidthMeter bandwidthMeter;

        private final int maxInitialBitrate;
        private final long minDurationForQualityIncreaseUs;
        private final long maxDurationForQualityDecreaseUs;
        private final long minDurationToRetainAfterDiscardUs;
        private final float bandwidthFraction;

        /**
         * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
         */
        public AdaptiveEvaluator(BandwidthMeter bandwidthMeter) {
            this(bandwidthMeter, DEFAULT_MAX_INITIAL_BITRATE,
                    DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
                    DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                    DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, DEFAULT_BANDWIDTH_FRACTION);
        }

        /**
         * @param bandwidthMeter                    Provides an estimate of the currently available bandwidth.
         * @param maxInitialBitrate                 The maximum bitrate in bits per second that should be assumed
         *                                          when bandwidthMeter cannot provide an estimate due to playback having only just started.
         * @param minDurationForQualityIncreaseMs   The minimum duration of buffered data required for
         *                                          the evaluator to consider switching to a higher quality format.
         * @param maxDurationForQualityDecreaseMs   The maximum duration of buffered data required for
         *                                          the evaluator to consider switching to a lower quality format.
         * @param minDurationToRetainAfterDiscardMs When switching to a significantly higher quality
         *                                          format, the evaluator may discard some of the media that it has already buffered at the
         *                                          lower quality, so as to switch up to the higher quality faster. This is the minimum
         *                                          duration of media that must be retained at the lower quality.
         * @param bandwidthFraction                 The fraction of the available bandwidth that the evaluator should
         *                                          consider available for use. Setting to a value less than 1 is recommended to account
         *                                          for inaccuracies in the bandwidth estimator.
         */
        public AdaptiveEvaluator(BandwidthMeter bandwidthMeter,
                                 int maxInitialBitrate,
                                 int minDurationForQualityIncreaseMs,
                                 int maxDurationForQualityDecreaseMs,
                                 int minDurationToRetainAfterDiscardMs,
                                 float bandwidthFraction) {
            this.bandwidthMeter = bandwidthMeter;
            this.maxInitialBitrate = maxInitialBitrate;
            this.minDurationForQualityIncreaseUs = minDurationForQualityIncreaseMs * 1000L;
            this.maxDurationForQualityDecreaseUs = maxDurationForQualityDecreaseMs * 1000L;
            this.minDurationToRetainAfterDiscardUs = minDurationToRetainAfterDiscardMs * 1000L;
            this.bandwidthFraction = bandwidthFraction;
        }

        @Override
        public void enable() {
            // Do nothing.
        }

        @Override
        public void disable() {
            // Do nothing.
        }

        /**
         * commentes added by shuai
         *
         * @param queue              A read only representation of the currently buffered {@link MediaChunk}s.
         * @param playbackPositionUs The current playback position.
         * @param formats            The formats from which to select, ordered by decreasing bandwidth.
         * @param evaluation         The evaluation.
         */
        @Override
        public void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs,
                             Format[] formats, Evaluation evaluation) {

            // get the buffered queue, in term of microseconds 1s = 10^6 us
            //playbackPoistionUs is the current player playback time
            long bufferedDurationUs = queue.isEmpty() ? 0
                    : queue.get(queue.size() - 1).endTimeUs - playbackPositionUs;
            Log.d(TAG, "BufferedTime" + String.valueOf(bufferedDurationUs) + " us, " + String.valueOf(bufferedDurationUs / 1000000) + " s");

            //get the current Format information
            Format current = evaluation.format;
            Log.d(TAG, "Current Format, ID: [" + current.id + "] FrameRate: [" + current.frameRate + "] " +
                    String.valueOf(current.bitrate) + " bps, " + String.valueOf((float) current.bitrate / 1000000) + " Mpbs");


            //get the future/next estimate video format based the return the bandwidth testing
            long estimatedBW = bandwidthMeter.getBitrateEstimate();  //Now get the current bandwidth again
            Log.d(TAG, "EstimatedBandWidth " + String.valueOf(estimatedBW) + " bps, " + String.valueOf(estimatedBW / 1000000) + " Mbps                        EffectiveBitrate: " + String.valueOf(bandwidthFraction) + "% of EBW: -> " + String.valueOf(estimatedBW * bandwidthFraction / 1000000) + " Mbps");


            // based on the current estimated Banwidth to return a idea format
            Format ideal = determineIdealFormat(formats, estimatedBW);
            //Get returned ideal bitrate
            String currentBPS = String.valueOf(ideal.bitrate);
            String currentMBPS = String.valueOf((float) ideal.bitrate / 1000000);
            String frameRate = String.valueOf(ideal.frameRate);
            Log.d(TAG, "Ideal Format: ID: [" + ideal.id + "] FrameRate: [" + frameRate + "] " +
                    currentBPS + " bps, " + currentMBPS + " Mpbs");


            // test if the  calculated idea/future/next video format's bitrate is bigger than the current one, set isHight == True
            boolean isHigher = ideal != null && current != null && ideal.bitrate > current.bitrate;
            // test if the future/next video format's bitrate is smaller than the current one, set isLower == True
            boolean isLower = ideal != null && current != null && ideal.bitrate < current.bitrate;

            if (isHigher) {
                if (bufferedDurationUs < minDurationForQualityIncreaseUs) {
                    // The ideal format is a higher quality, but we have insufficient buffer to
                    // safely switch up. Defer switching up for now.
                    ideal = current;
                    Log.d(TAG, "not enough buffer left to improve video frame quality");
                } else if (bufferedDurationUs >= minDurationToRetainAfterDiscardUs) {
                    // We're switching from an SD stream to a stream of higher resolution. Consider
                    // discarding already buffered media chunks. Specifically, discard media chunks starting
                    // from the first one that is of lower bandwidth, lower resolution and that is not HD.
                    Log.d(TAG, "Trying to Improve the Video quality");
                    for (int i = 1; i < queue.size(); i++) { // interate the Buffer queue
                        MediaChunk thisChunk = queue.get(i);
                        long durationBeforeThisSegmentUs = thisChunk.startTimeUs - playbackPositionUs;
                        if (durationBeforeThisSegmentUs >= minDurationToRetainAfterDiscardUs
                                && thisChunk.format.bitrate < ideal.bitrate
                                && thisChunk.format.height < ideal.height
                                && thisChunk.format.height < 720
                                && thisChunk.format.width < 1280) {
                            // Discard chunks from this one onwards, change the buffer queue size
                            evaluation.queueSize = i;
                            break;
                        }
                    }
                }
            } else if (isLower && current != null
                    && bufferedDurationUs >= maxDurationForQualityDecreaseUs) {
                // The ideal format is a lower quality, but we have sufficient buffer to defer switching
                // down for now.
                Log.d(TAG, "The ideal format is a lower quality, but we have sufficient buffer to defer switching down for now");
                ideal = current;
            }

            if (current != null && ideal != current) {
                evaluation.trigger = Chunk.TRIGGER_ADAPTIVE;
            }
            evaluation.format = ideal;
        }// end of evaluate()
        // in this function, you change the evalution.format, evaluation.trigger, evaluation.queueSize.

        /**
         * comments added by shuai
         * Compute the ideal format ignoring buffer health.
         */
        private Format determineIdealFormat(Format[] formats, long bitrateEstimate) {
            //calculate the effective Bitrate, if its the beginnging, assign maxInitialBitrate to it
            //If its not the beginning, then assign "bitrateEstimate * bandwidthFraction"
            long effectiveBitrate = bitrateEstimate == BandwidthMeter.NO_ESTIMATE
                    ? maxInitialBitrate : (long) (bitrateEstimate * bandwidthFraction);
            for (int i = 0; i < formats.length; i++) {
                Format format = formats[i];
                if (format.bitrate <= effectiveBitrate) {
                    return format;
                }
            }
            // We didn't manage to calculate a suitable format. Return the lowest quality format.
            return formats[formats.length - 1];
        }

    }

    /**
     * MyAdaptiveEvaluator is added by shuai
     * Any Adaptive algorithm change will be happening here.
     */

    public static final class MyAdaptiveEvaluator implements FormatEvaluator {

        public static final int DEFAULT_MAX_INITIAL_BITRATE = 800000;  //bps, 800Kbps
        public static final int DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS = 10000; //ms, 1s
        public static final int DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS = 25000; //ms, 2.5s
        public static final int DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 25000; //ms, 2.5s

        // DEFAULT_BANDWIDTH_FRACTION: What percentage of the current network bandwidth you can use
        //public static final float DEFAULT_BANDWIDTH_FRACTION = 0.75f;
        public static final float DEFAULT_BANDWIDTH_FRACTION = 0.1f;

        //added by shuai
        private static final String TAG = "FormatEvaluator";

        private final BandwidthMeter bandwidthMeter;

        private final int maxInitialBitrate;
        private final long minDurationForQualityIncreaseUs;
        private final long maxDurationForQualityDecreaseUs;
        private final long minDurationToRetainAfterDiscardUs;
        private final float bandwidthFraction;

        /**
         * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
         */
        public MyAdaptiveEvaluator(BandwidthMeter bandwidthMeter) {
            this(bandwidthMeter, DEFAULT_MAX_INITIAL_BITRATE,
                    DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
                    DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                    DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, DEFAULT_BANDWIDTH_FRACTION);
        }

        /**
         * @param bandwidthMeter                    Provides an estimate of the currently available bandwidth.
         * @param maxInitialBitrate                 The maximum bitrate in bits per second that should be assumed
         *                                          when bandwidthMeter cannot provide an estimate due to playback having only just started.
         * @param minDurationForQualityIncreaseMs   The minimum duration of buffered data required for
         *                                          the evaluator to consider switching to a higher quality format.
         * @param maxDurationForQualityDecreaseMs   The maximum duration of buffered data required for
         *                                          the evaluator to consider switching to a lower quality format.
         * @param minDurationToRetainAfterDiscardMs When switching to a significantly higher quality
         *                                          format, the evaluator may discard some of the media that it has already buffered at the
         *                                          lower quality, so as to switch up to the higher quality faster. This is the minimum
         *                                          duration of media that must be retained at the lower quality.
         * @param bandwidthFraction                 The fraction of the available bandwidth that the evaluator should
         *                                          consider available for use. Setting to a value less than 1 is recommended to account
         *                                          for inaccuracies in the bandwidth estimator.
         */
        public MyAdaptiveEvaluator(BandwidthMeter bandwidthMeter,
                                   int maxInitialBitrate,
                                   int minDurationForQualityIncreaseMs,
                                   int maxDurationForQualityDecreaseMs,
                                   int minDurationToRetainAfterDiscardMs,
                                   float bandwidthFraction) {
            this.bandwidthMeter = bandwidthMeter;
            this.maxInitialBitrate = maxInitialBitrate;
            this.minDurationForQualityIncreaseUs = minDurationForQualityIncreaseMs * 1000L;
            this.maxDurationForQualityDecreaseUs = maxDurationForQualityDecreaseMs * 1000L;
            this.minDurationToRetainAfterDiscardUs = minDurationToRetainAfterDiscardMs * 1000L;
            this.bandwidthFraction = bandwidthFraction;
        }

        @Override
        public void enable() {
            // Do nothing.
        }

        @Override
        public void disable() {
            // Do nothing.
        }

        /**
         * commentes added by shuai
         *
         * @param queue              A read only representation of the currently buffered {@link MediaChunk}s.
         * @param playbackPositionUs The current playback position.
         * @param formats            The formats from which to select, ordered by decreasing bandwidth.
         * @param evaluation         The evaluation.
         */
        @Override
        public void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs,
                             Format[] formats, Evaluation evaluation) {

            // get the buffered queue, in term of microseconds 1s = 10^6 us
            //playbackPoistionUs is the current player playback time
            long bufferedDurationUs = queue.isEmpty() ? 0
                    : queue.get(queue.size() - 1).endTimeUs - playbackPositionUs;

            //get the current Format information
            Format current = evaluation.format;
            if (current == null) {
                Log.d(TAG, "Initially Playing");
            } else {
                Log.d(TAG, "Current Format, ID[ " + current.id + " ] FrameRate[ " +
                        current.frameRate + " ] " + "current birate: " + String.valueOf(current.bitrate) + " bps, " +
                        String.valueOf((float) current.bitrate / 1000000) + " Mpbs");
            }


            //get the future/next estimate video format based the return the bandwidth testing
            long estimatedBW = bandwidthMeter.getBitrateEstimate();  //Now get the current bandwidth again

            // based on the current estimated Banwidth to return a idea format
            Format ideal = determineIdealFormat(formats, estimatedBW);
            //Get returned ideal bitrate
            String idealBPS = String.valueOf(ideal.bitrate);
            String idealMBPS = String.valueOf((float) ideal.bitrate / 1000000);
            String frameRate = String.valueOf(ideal.frameRate);

            Log.d(TAG, "BufferedTime " + String.valueOf(bufferedDurationUs) + " us, " + String.valueOf(bufferedDurationUs / 1000000.0) +
                    " s, " + "EstimatedBandWidth " + String.valueOf(estimatedBW) + " bps, " + String.valueOf(estimatedBW / 1000000) +
                    " Mbps, EffectiveBitrate: " + String.valueOf(bandwidthFraction) + "% of EBW: -> " +
                    String.valueOf(estimatedBW * bandwidthFraction / 1000000.0) + " Mbps," +
                    "IdealFormat: ID[ " + ideal.id + " ], FrameRate[ " + ideal.frameRate + " ] " + " Idealbps " +
                    idealBPS + " bps, " + idealMBPS + " Mpbs");

            // test if the  calculated idea/future/next video format's bitrate is bigger than the current one, set isHight == True
            boolean isHigher = ideal != null && current != null && ideal.bitrate > current.bitrate;
            // test if the future/next video format's bitrate is smaller than the current one, set isLower == True
            boolean isLower = ideal != null && current != null && ideal.bitrate < current.bitrate;

            if (isHigher) {
                if (bufferedDurationUs < minDurationForQualityIncreaseUs) {
                    // The ideal format is a higher quality, but we have insufficient buffer to
                    // safely switch up. Defer switching up for now.
                    ideal = current;
                    Log.d(TAG, "not enough buffer left to improve video frame quality because BufferedDuration: " +
                            String.valueOf(bufferedDurationUs) + " is < minDurationForQualityInscreseUs " +
                            String.valueOf(minDurationForQualityIncreaseUs));
                } else if (bufferedDurationUs >= minDurationToRetainAfterDiscardUs) {
                    // We're switching from an SD stream to a stream of higher resolution. Consider
                    // discarding already buffered media chunks. Specifically, discard media chunks starting
                    // from the first one that is of lower bandwidth, lower resolution and that is not HD.
                    int savedIndex = 0;
                    Log.d(TAG, "Current Buffer size: " + String.valueOf(queue.size()));
                    for (int i = 1; i < queue.size(); i++) {
//                         iterate the queue
//                        check if any of the queued segment's (thisChunk.startTimeUs - playbackPositionUs) is greater minDurationToRetainAfterDiscardUs
//                         if it is, then check this bitrate, height, and width again the ideal segement
//                         if it is still true, then change the queue size to this point
//                         if it is not, then no need to update the queue size
                        MediaChunk thisChunk = queue.get(i);
                        long durationBeforeThisSegmentUs = thisChunk.startTimeUs - playbackPositionUs;
                        if (durationBeforeThisSegmentUs >= minDurationToRetainAfterDiscardUs
                                && thisChunk.format.bitrate < ideal.bitrate
                                && thisChunk.format.height < ideal.height
                                && thisChunk.format.height < 720
                                && thisChunk.format.width < 1280) {
                            // Discard chunks from this one onwards, change the buffer queue size
                            evaluation.queueSize = i;
                            savedIndex = i;
                            break;
                        }else{
                            Log.d(TAG, "NO need to upgrade the frame quality because the following reason: "  +
                                    "durationBeforeThisSegmentUs>=minDurationToRetainAfterDiscardUs is  " +
                            String.valueOf(durationBeforeThisSegmentUs >= minDurationToRetainAfterDiscardUs) + " Or " +
                            " thisChunk.format.bitrate < ideal.bitrate is " + String.valueOf(thisChunk.format.bitrate < ideal.bitrate) +
                            " Or thisChunk.format.height < ideal.height is " + String.valueOf(thisChunk.format.height < ideal.height) +
                            " Or thisChunk.format.height < 720 is " + String.valueOf(thisChunk.format.height < 720) +
                            " Or thisChunk.format.width < 1280 is " + String.valueOf(thisChunk.format.width < 1280) +
                            " savedIndex=" + String.valueOf(savedIndex));
                        }
                    }
                    //following for loop is added by shuai
                    for (int i = 1; i < savedIndex; i++) {
                        MediaChunk thisChunk = queue.get(i);
                        Log.d(TAG, "Trying to Improve the Video quality! now the evaluation reuslt is " +
                                "evalution.queueSize = " + String.valueOf(evaluation.queueSize) +
                                " Current Buffer details: " + String.valueOf(i) + ":" + "bitrate " +
                                String.valueOf(thisChunk.format.bitrate) + " height " + String.valueOf(thisChunk.format.height) +
                                " width " + String.valueOf(thisChunk.format.width));
                    }
                }
            } else if (isLower && current != null
                    && bufferedDurationUs >= maxDurationForQualityDecreaseUs) {
                // The ideal format is a lower quality, but we have sufficient buffer to defer switching
                // down for now.
                Log.d(TAG, "The ideal format is a lower quality, but we have sufficient buffer to defer switching down for now" +
                        " Current Buffer size is " + String.valueOf(queue.size()));
                ideal = current;
            }

            if (current != null && ideal != current) {
                evaluation.trigger = Chunk.TRIGGER_ADAPTIVE;
            }
            evaluation.format = ideal;
        }// end of evaluate()
        // in this function, you change the evalution.format, evaluation.trigger, evaluation.queueSize.

        /**
         * comments added by shuai
         * Compute the ideal format ignoring buffer health.
         */
        private Format determineIdealFormat(Format[] formats, long bitrateEstimate) {
            //calculate the effective Bitrate, if its the beginnging, assign maxInitialBitrate to it
            //If its not the beginning, then assign "bitrateEstimate * bandwidthFraction"
            long effectiveBitrate = bitrateEstimate == BandwidthMeter.NO_ESTIMATE
                    ? maxInitialBitrate : (long) (bitrateEstimate * bandwidthFraction);
            for (int i = 0; i < formats.length; i++) {
                Format format = formats[i];
                if (format.bitrate <= effectiveBitrate) {
                    return format;
                }
            }
            // We didn't manage to calculate a suitable format. Return the lowest quality format.
            return formats[formats.length - 1];
        }

    }
}
