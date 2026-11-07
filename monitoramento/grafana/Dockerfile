# Grafana do Quack PDV: fonte de dados e paineis embutidos na imagem (provisionamento por arquivo).
# Imagem publicada por .github/workflows/monitoramento.yml como quack-monitor-grafana.
# A senha do admin vem de GF_SECURITY_ADMIN_PASSWORD (variavel da stack); o login e obrigatorio, sem acesso anonimo.
FROM grafana/grafana-oss:12.1.1

ENV GF_AUTH_ANONYMOUS_ENABLED=false \
    GF_USERS_ALLOW_SIGN_UP=false \
    GF_ANALYTICS_REPORTING_ENABLED=false \
    GF_ANALYTICS_CHECK_FOR_UPDATES=false \
    GF_SECURITY_DISABLE_GRAVATAR=true \
    GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH=/etc/grafana/dashboards/sincronizacao-lojas.json

# os paineis ficam fora de /var/lib/grafana (volume): uma imagem nova atualiza os paineis sem mexer no volume
COPY provisioning/ /etc/grafana/provisioning/
COPY dashboards/ /etc/grafana/dashboards/
