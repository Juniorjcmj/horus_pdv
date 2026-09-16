/**
 * Arquivo: src/pages/Auth/LoginPage.tsx
 * Objetivo: renderiza autenticação do usuário e inicialização da sessão no frontend.
 * Entradas esperadas: recebe callbacks de sucesso e navegação para cadastro/recuperação.
 */
import { LogIn } from "lucide-react";
import { useState } from "react";
import useRecaptchaV3 from "@/hooks/Security/useRecaptchaV3";
import AuthLayout from "./AuthLayout";
import { EmailField, FeedbackMessage, PasswordField } from "./AuthFields";
import LoadingButton from "@/components/Loading/LoadingButton";
import type { AuthActionResult } from "./types";

type LoginPageProps = {
  onLogin: (
    email: string,
    password: string,
    remember: boolean,
    recaptchaToken?: string,
  ) => Promise<AuthActionResult>;
  onOpenForgotPassword: () => void;
  onOpenRegister: () => void;
  onOpenLanding?: () => void;
};

export default function LoginPage({
  onLogin,
  onOpenForgotPassword,
  onOpenRegister,
  onOpenLanding,
}: LoginPageProps) {
  const { executeRecaptcha, isRecaptchaConfigured } = useRecaptchaV3();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [remember, setRemember] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [feedback, setFeedback] = useState<AuthActionResult | null>(null);

  const runRecaptcha = () =>
    isRecaptchaConfigured ? executeRecaptcha("login") : Promise.resolve("");

  const handleLogin = async () => {
    if (!email.trim() || !password.trim()) {
      setFeedback({
        success: false,
        message: "Informe e-mail e senha para entrar.",
      });
      return;
    }

    setIsSubmitting(true);
    try {
      const recaptchaToken = await runRecaptcha();
      setFeedback(await onLogin(email, password, remember, recaptchaToken));
    } catch (error) {
      setFeedback(
        toErrorResult(
          error,
          "Não foi possível concluir a validação de segurança.",
        ),
      );
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Bem-vindo de volta!"
      description="Use seu e-mail e senha para acessar o painel."
    >
      {onOpenLanding ? (
        <div className="mb-4 pb-3 border-b border-border-primary">
          <button
            type="button"
            onClick={onOpenLanding}
            className="inline-flex items-center gap-1.5 text-xs font-semibold text-accent hover:text-hover-accent transition cursor-pointer"
          >
            ← Conhecer o Quack PDV (Apresentação do Sistema)
          </button>
        </div>
      ) : null}
      <EmailField value={email} onChange={setEmail} onEnter={handleLogin} />
      <PasswordField
        label="Senha"
        value={password}
        show={showPassword}
        onChange={setPassword}
        onToggle={() => setShowPassword((current) => !current)}
        onEnter={handleLogin}
      />

      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <label className="flex items-center gap-2 text-sm text-text-secondary">
          <input
            type="checkbox"
            checked={remember}
            onChange={(event) => setRemember(event.target.checked)}
          />
          Manter sessão conectada
        </label>
        <button
          type="button"
          onClick={onOpenForgotPassword}
          disabled={isSubmitting}
          className="text-left text-sm font-semibold text-secondary hover:text-hover-secondary disabled:opacity-60 sm:text-right"
        >
          Esqueci minha senha
        </button>
      </div>

      <LoadingButton
        type="button"
        onClick={handleLogin}
        isLoading={isSubmitting}
        loadingLabel="Entrando..."
        className="btn-primary inline-flex min-h-11 w-full items-center justify-center gap-2"
      >
        <LogIn size={16} />
        Entrar
      </LoadingButton>

      <div className="flex flex-wrap items-center justify-center gap-2 text-sm text-text-secondary">
        <span>Não tem uma conta?</span>
        <button
          type="button"
          onClick={onOpenRegister}
          disabled={isSubmitting}
          className="font-semibold text-secondary transition hover:text-hover-secondary disabled:opacity-60"
        >
          Criar cadastro
        </button>
      </div>

      <FeedbackMessage result={feedback} />
    </AuthLayout>
  );
}

function toErrorResult(error: unknown, fallback: string): AuthActionResult {
  return {
    success: false,
    message: error instanceof Error ? error.message : fallback,
  };
}
