package com.mekki.scrcpy.model;

import java.nio.ByteBuffer;

/**
 * Created by Alexandr Golovach on 27.06.16.
 * https://www.github.com/alexmprog/VideoCodec
 */

public class VideoPacket extends MediaPacket<VideoPacket> {

    public Flag flag;
    public long presentationTimeStamp;
    public byte[] data;

    public VideoPacket() {
    }

    public VideoPacket(Type type, Flag flag, long presentationTimeStamp, byte[] data) {
        this.type = type;
        this.flag = flag;
        this.presentationTimeStamp = presentationTimeStamp;
        this.data = data;
    }

    // create packet from byte array
    public VideoPacket fromArray(byte[] values) {
        VideoPacket videoPacket = new VideoPacket();

        // should be a type value - 1 byte
        byte typeValue = values[0];
        // should be a flag value - 1 byte
        byte flagValue = values[1];

        videoPacket.type = Type.getType(typeValue);
        videoPacket.flag = Flag.getFlag(flagValue);

        // should be 8 bytes for timestamp
        byte[] timeStamp = new byte[8];
        System.arraycopy(values, 2, timeStamp, 0, 8);
        videoPacket.presentationTimeStamp = ByteUtils.bytesToLong(timeStamp);

        // all other bytes is data
        int dataLength = values.length - 10;
        byte[] data = new byte[dataLength];
        System.arraycopy(values, 10, data, 0, dataLength);
        videoPacket.data = data;

        return videoPacket;
    }

    public static VideoPacket readHead(byte[] values) {
        VideoPacket videoPacket = new VideoPacket();

        // should be a type value - 1 byte
        byte typeValue = values[0];
        // should be a flag value - 1 byte
        byte flagValue = values[1];

        videoPacket.type = Type.getType(typeValue);
        videoPacket.flag = VideoPacket.Flag.getFlag(flagValue);

        // should be 8 bytes for timestamp
        byte[] timeStamp = new byte[8];
        System.arraycopy(values, 2, timeStamp, 0, 8);
        videoPacket.presentationTimeStamp = ByteUtils.bytesToLong(timeStamp);

        videoPacket.data = null;

        return videoPacket;
    }

    public int headLength(){
        return 10;
    }

    // create byte array
    public static byte[] toArray(Type type, Flag flag, long presentationTimeStamp, byte[] data) {

        // should be 4 bytes for packet size
        byte[] bytes = ByteUtils.intToBytes(10 + data.length);

        int packetSize = 14 + data.length; // 4 - inner packet size 1 - type + 1 - flag + 8 - timeStamp + data.length
        byte[] values = new byte[packetSize];

        System.arraycopy(bytes, 0, values, 0, 4);

        // set type value
        values[4] = type.getType();
        // set flag value
        values[5] = flag.getFlag();
        // set timeStamp
        byte[] longToBytes = ByteUtils.longToBytes(presentationTimeStamp);
        System.arraycopy(longToBytes, 0, values, 6, longToBytes.length);

        // set data array
        System.arraycopy(data, 0, values, 14, data.length);
        return values;
    }

    // should call on inner packet
    public static boolean isVideoPacket(byte[] values) {
        return values[0] == Type.VIDEO.getType();
    }

    public static StreamSettings getStreamSettings(byte[] buffer) {
        return getStreamSettings(buffer, false);
    }

    public static StreamSettings getStreamSettings(byte[] buffer, boolean hevc) {
        StreamSettings streamSettings = new StreamSettings();
        if (hevc) {
            // HEVC MediaCodec expects VPS/SPS/PPS together in csd-0
            streamSettings.sps = ByteBuffer.wrap(buffer);
            streamSettings.pps = null;
            return streamSettings;
        }

        int ppsStart = -1;
        for (int i = 4; i + 4 < buffer.length; i++) {
            int prefix = buffer[i] == 0 && buffer[i+1] == 0
                    ? (buffer[i+2] == 1 ? 3 : (buffer[i+2] == 0 && buffer[i+3] == 1 ? 4 : 0)) : 0;
            if (prefix > 0 && (buffer[i + prefix] & 31) == 8) { ppsStart = i; break; }
        }
        if (ppsStart < 0) throw new IllegalArgumentException("H.264 configuration is missing PPS");
        streamSettings.sps = ByteBuffer.wrap(java.util.Arrays.copyOfRange(buffer, 0, ppsStart));
        streamSettings.pps = ByteBuffer.wrap(java.util.Arrays.copyOfRange(buffer, ppsStart, buffer.length));

        return streamSettings;
    }

    public byte[] toByteArray() {
        return toArray(type, flag, presentationTimeStamp, data);
    }

    public enum Flag {

        FRAME((byte) 0), KEY_FRAME((byte) 1), CONFIG((byte) 2), END((byte) 4);

        private byte type;

        Flag(byte type) {
            this.type = type;
        }

        public static Flag getFlag(byte value) {
            for (Flag type : Flag.values()) {
                if (type.getFlag() == value) {
                    return type;
                }
            }

            return null;
        }

        public byte getFlag() {
            return type;
        }
    }

    public static class StreamSettings {
        public ByteBuffer pps;
        public ByteBuffer sps;
    }
}
