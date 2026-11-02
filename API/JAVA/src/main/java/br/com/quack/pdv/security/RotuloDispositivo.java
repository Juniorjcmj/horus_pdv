package br.com.quack.pdv.security;

import java.util.Locale;

/** Rotulo do dispositivo a partir do user-agent (igual ao BuildDeviceLabel do .NET). */
public final class RotuloDispositivo {

    private RotuloDispositivo() {}

    public static String de(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("firefox")) return "Navegador - Firefox";
        if (ua.contains("edg")) return "Navegador - Edge";
        if (ua.contains("chrome")) return "Navegador - Chrome";
        if (ua.contains("safari")) return "Navegador - Safari";
        return "Dispositivo web";
    }
}
