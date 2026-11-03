package br.com.quack.pdv.security;

/** a***b@dominio (igual ao MaskEmail do .NET). */
public final class MascaraEmail {

    private MascaraEmail() {}

    public static String mascarar(String email) {
        String[] parts = email.split("@", 2);
        if (parts.length != 2 || parts[0].isEmpty()) return email;
        String local = parts[0];
        String prefixo = local.length() == 1 ? local : local.charAt(0) + "***" + local.charAt(local.length() - 1);
        return prefixo + "@" + parts[1];
    }
}
