package com.mekki.scrcpy.usb;

import java.util.ArrayList;
import java.util.List;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkAddress;
import android.net.LinkProperties;
import java.net.InetAddress;

/** Explicit candidates for one device; no discovery or background probing. */
public final class Endpoint {
    public final String host;
    public final int port;
    private Endpoint(String host, int port) { this.host = host; this.port = port; }
    public static Endpoint parse(String value) {
        value = value.trim();
        int split = value.lastIndexOf(':');
        if (split < 1) throw new IllegalArgumentException("Include the ADB port, for example device:6666");
        String host = value.substring(0, split);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        else if (host.contains(":")) throw new IllegalArgumentException("Put IPv6 addresses in brackets: [address]:port");
        if (host.isEmpty() || !host.matches("[a-zA-Z0-9_.:%-]+")) throw new IllegalArgumentException("Invalid device address");
        int port;
        try { port = Integer.parseInt(value.substring(split + 1)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid ADB port"); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("ADB port must be between 1 and 65535");
        return new Endpoint(host, port);
    }
    public static List<Endpoint> candidates(String text) {
        String[] parts = text.split(",", -1);
        if (parts.length > 4) throw new IllegalArgumentException("Use at most four addresses for the same device");
        List<Endpoint> result = new ArrayList<>();
        for (String part : parts) result.add(parse(part));
        return result;
    }
    static boolean sameSubnet(byte[] a, byte[] b, int prefix) {
        if (a.length != b.length || prefix < 0 || prefix > a.length * 8) return false;
        for (int i = 0; i < a.length; i++) {
            int bits = Math.min(8, Math.max(0, prefix - i * 8));
            int mask = bits == 0 ? 0 : (255 << (8 - bits)) & 255;
            if ((a[i] & mask) != (b[i] & mask)) return false;
        }
        return true;
    }
    public static Network localWifi(Context context, String host) {
        // Inspect only an explicitly entered numeric address. Never discover peers.
        if (!host.matches("[0-9.]+")) return null;
        try {
            byte[] target = InetAddress.getByName(host).getAddress();
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                LinkProperties links = manager.getLinkProperties(network);
                if (caps == null || links == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                for (LinkAddress address : links.getLinkAddresses()) {
                    if (sameSubnet(target, address.getAddress().getAddress(), address.getPrefixLength())) return network;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
    public static List<Endpoint> ordered(Context context, String text) {
        List<Endpoint> candidates = candidates(text);
        candidates.sort((a,b) -> Boolean.compare(localWifi(context,b.host) != null, localWifi(context,a.host) != null));
        return candidates;
    }
    @Override public String toString() { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
}
