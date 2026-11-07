# Prometheus do Quack PDV: configuracao embutida na imagem (a stack do Portainer e colada a mao e o no nao tem o
# repositorio). Imagem publicada por .github/workflows/monitoramento.yml como quack-monitor-prometheus.
FROM prom/prometheus:v3.5.1

USER root
COPY prometheus.yml /etc/prometheus/prometheus.yml
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
# arquivo de credencial do dono "nobody" (usuario do Prometheus); o entrypoint o preenche e passa para 0400
RUN chmod 755 /usr/local/bin/entrypoint.sh \
    && touch /etc/prometheus/metrics-token \
    && chown nobody:nobody /etc/prometheus/metrics-token \
    && chmod 600 /etc/prometheus/metrics-token
USER nobody

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
