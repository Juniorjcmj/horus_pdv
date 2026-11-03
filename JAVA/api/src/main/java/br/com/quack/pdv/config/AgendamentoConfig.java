package br.com.quack.pdv.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Liga as rotinas periodicas da API (nova tentativa da baixa de lotes e, na Fase 4, a fila fiscal minima). */
@Configuration
@EnableScheduling
public class AgendamentoConfig {}
