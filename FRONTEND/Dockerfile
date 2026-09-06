# syntax=docker/dockerfile:1
# Arquivo: FRONTEND/Dockerfile
# Objetivo: build multi-stage do frontend (Vite) do Hórus PDV, servido por nginx em produção.
# Contexto de build esperado: FRONTEND (ver .github/workflows/docker-publish.yml).
#
# As URLs de API (VITE_*_API_URL) são resolvidas em BUILD TIME pelo Vite, não em runtime —
# por isso o hostname da API entra aqui como build-arg e é aplicado em cima do .env.prod já
# versionado (que traz o padrão de nome de cada variável; só o host muda).

FROM node:20-alpine AS build
WORKDIR /app

ARG VITE_API_ORIGIN=https://api.horuspdv.local
ARG VITE_RECAPTCHA_SITE_KEY=

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# .env.prod já traz todas as VITE_*_API_URL com o placeholder "https://api.horuspdv.local" —
# só troca o host pelo valor real de build (build:prod carrega .env.prod via --mode prod).
RUN sed -i "s#https://api.horuspdv.local#${VITE_API_ORIGIN}#g" .env.prod \
    && sed -i "s#^VITE_RECAPTCHA_SITE_KEY=.*#VITE_RECAPTCHA_SITE_KEY=${VITE_RECAPTCHA_SITE_KEY}#" .env.prod

RUN npm run build:prod

FROM nginx:1.27-alpine AS final
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
