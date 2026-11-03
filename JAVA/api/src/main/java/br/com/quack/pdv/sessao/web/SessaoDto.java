package br.com.quack.pdv.sessao.web;

/** Sessao devolvida pela API (equivale ao SecuritySessionDto do .NET). */
public record SessaoDto(String id, String device, String location, String ip, String lastActive, boolean current,
                        String platform) {
}
