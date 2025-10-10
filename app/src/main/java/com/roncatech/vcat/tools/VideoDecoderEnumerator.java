package com.roncatech.vcat.tools;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCodec;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.roncatech.vcat.video.StrictRenderersFactoryV2;

import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.List;

public class VideoDecoderEnumerator {

    // Enum to define supported MIME types
    public enum MimeType {
        H264("video/avc"),
        H265("video/hevc"),
        VP9("video/x-vnd.on2.vp9"),
        AV1("video/av01"),
        VVC("video/vvc");

        private final String mimeString;

        MimeType(String mimeString) {
            this.mimeString = mimeString;
        }

        @NonNull
        @Contract(pure = true)
        public String toString() {
            return mimeString;
        }

        public static MimeType fromString(String text) {
            for (MimeType type : MimeType.values()) {
                if (type.mimeString.equalsIgnoreCase(text)) {
                    return type;
                }
            }
            return null; // Unknown type
        }

        // ✅ Static method to return all MIME type strings as an array (No Streams)
        public static List<String> getAllVideoCodecMimeTypeStrings() {
            MimeType[] values = MimeType.values();
            List<String> ret = new ArrayList<>();
            for (int i = 0; i < values.length; i++) {
                ret.add(values[i].toString());
            }
            return ret;
        }

    }

    public static class DecoderSet
    {
        public final MimeType mimeType;
        public final List<String> decoders;

        public DecoderSet(MimeType mimeType, List<String> decoders){
            this.mimeType = mimeType;
            this.decoders = decoders;
        }
    }

    public static DecoderSet getDecodersForMimeType(MimeType mimeType){

        List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> codecInfos;
        try {
            codecInfos = MediaCodecUtil.getDecoderInfos(mimeType.toString(), false, false);
        } catch (MediaCodecUtil.DecoderQueryException e) {
            codecInfos = new ArrayList<>();
        }

        List<String> decoders = new ArrayList<>();

        if(mimeType == MimeType.AV1){
            decoders.add(StrictRenderersFactoryV2.vcatDav1dName);
        }

        for (com.google.android.exoplayer2.mediacodec.MediaCodecInfo codecInfo : codecInfos) {
            decoders.add(codecInfo.name);
        }

        return new DecoderSet(mimeType, decoders);
    }
    public static List<DecoderSet> getAllDecoders(List<MimeType> mimeTypes) {
        List<DecoderSet> ret = new ArrayList<>();

        for (MimeType mimeType : mimeTypes) {
            DecoderSet curSet =getDecodersForMimeType(mimeType);

            if(!curSet.decoders.isEmpty()) {
                ret.add(curSet);
            }
        }

        return ret;
    }

    public static String decodersToString(List<DecoderSet> decoderList) {
        StringBuilder output = new StringBuilder();

        for (DecoderSet set : decoderList) {
            output.append("Decoders for: ").append(set.mimeType.toString()).append("\n");
            for (String decoder : set.decoders) {
                output.append("  ").append(decoder).append("\n");
            }
        }

        return output.toString();
    }

}



