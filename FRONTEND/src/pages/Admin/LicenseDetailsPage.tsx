/**
 * Arquivo: src/pages/Admin/LicenseDetailsPage.tsx
 * Objetivo: documenta o modelo de licença open source, créditos obrigatórios e status de uso da versão atual.
 * Entradas esperadas: não recebe props; renderiza conteúdo estático sobre termos de uso do projeto.
 */

import { BadgeCheck, BookMarked, Copyright, Scale } from "lucide-react";
import PageHeader from "@/components/Admin/PageHeader";
import PageLayout from "@/layout/PageLayout";

export default function LicenseDetailsPage() {
  return (
    <PageLayout className="space-y-4 py-4 md:space-y-6 md:py-6 lg:py-8">
      <PageHeader
        title="Detalhes da Licença"
        description="Informações de uso do Quack PDV como projeto open source."
      />

      <section className="card overflow-hidden">
        <div className="border-b border-border-primary bg-gradient-to-r from-secondary/8 via-bg-light to-accent/8 px-4 py-4 md:px-5">
          <h2 className="text-lg font-semibold text-text-primary">Licença de uso da versão atual</h2>
          <p className="mt-1 text-sm text-text-secondary">
            Esta instalação está vinculada ao modelo gratuito com atribuição de créditos.
          </p>
        </div>

        <div className="space-y-3 p-4 md:p-5">
          <article className="rounded-xl border border-border-primary bg-bg-primary p-4">
            <p className="inline-flex items-center gap-2 text-sm font-semibold text-text-primary">
              <Scale size={16} className="text-accent" />
              Modelo
            </p>
            <p className="mt-2 text-sm text-text-secondary">
              O Quack PDV pode ser utilizado gratuitamente nesta versão, para estudo, operação
              e evolução do projeto.
            </p>
          </article>

          <article className="rounded-xl border border-border-primary bg-bg-primary p-4">
            <p className="inline-flex items-center gap-2 text-sm font-semibold text-text-primary">
              <Copyright size={16} className="text-accent" />
              Créditos obrigatórios
            </p>
            <p className="mt-2 text-sm text-text-secondary">
              Ao usar, adaptar ou redistribuir, mantenha a referência ao projeto original e ao
              autor nos devidos pontos de documentação e interface.
            </p>
            <div className="mt-3 space-y-1 text-sm text-text-secondary">
              <p>
                Autor: <strong className="text-text-primary">Flávio Oliveira</strong>
              </p>
              <p>
                GitHub:{" "}
                <a
                  href="https://github.com/flaviooliveira-code"
                  target="_blank"
                  rel="noreferrer"
                  className="text-accent hover:underline"
                >
                  github.com/flaviooliveira-code
                </a>
              </p>
              <p>
                LinkedIn:{" "}
                <a
                  href="https://www.linkedin.com/in/fladoliveira"
                  target="_blank"
                  rel="noreferrer"
                  className="text-accent hover:underline"
                >
                  linkedin.com/in/fladoliveira
                </a>
              </p>
            </div>
          </article>

          <article className="rounded-xl border border-border-primary bg-bg-primary p-4">
            <p className="inline-flex items-center gap-2 text-sm font-semibold text-text-primary">
              <BookMarked size={16} className="text-accent" />
              Evolução do projeto
            </p>
            <p className="mt-2 text-sm text-text-secondary">
              Criado em 2020 e evoluído continuamente até esta versão de 2026, com melhorias de
              arquitetura e experiência de uso.
            </p>
          </article>

          <article className="rounded-xl border border-success/30 bg-success/10 p-4">
            <p className="inline-flex items-center gap-2 text-sm font-semibold text-success">
              <BadgeCheck size={16} />
              Status da licença atual
            </p>
            <p className="mt-2 text-sm text-success">
              Uso gratuito ativo para esta versão open source, respeitando os créditos e termos
              definidos pelo mantenedor.
            </p>
          </article>
        </div>
      </section>
    </PageLayout>
  );
}
