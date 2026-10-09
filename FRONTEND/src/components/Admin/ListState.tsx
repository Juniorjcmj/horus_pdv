import { AlertCircle, Inbox, RefreshCw } from "lucide-react";

type ListStateProps = {
  title: string;
  description?: string;
  loading?: boolean;
  error?: boolean;
  actionLabel?: string;
  onAction?: () => void;
};

export default function ListState({ title, description, loading, error, actionLabel, onAction }: ListStateProps) {
  const Icon = error ? AlertCircle : Inbox;
  return (
    <div role={error ? "alert" : "status"} className="rg-admin-list-state mx-auto flex max-w-xl flex-col items-center gap-3 px-4 py-8 text-center">
      {loading ? (
        <div aria-hidden="true" className="flex w-full flex-col gap-3 motion-safe:animate-pulse">
          <div className="h-3 w-3/4 rounded-lg bg-bg-gray-theme" />
          <div className="h-3 w-full rounded-lg bg-bg-gray-theme" />
          <div className="h-3 w-1/2 rounded-lg bg-bg-gray-theme" />
        </div>
      ) : <Icon size={24} aria-hidden="true" className={error ? "text-primary" : "text-text-secondary"} />}
      <p className="text-base font-semibold text-text-primary">{title}</p>
      {description && <p className="text-sm text-text-secondary">{description}</p>}
      {onAction && actionLabel && (
        <button type="button" className="btn-outline-secondary inline-flex min-h-11 items-center gap-2" onClick={onAction}>
          {error && <RefreshCw size={16} aria-hidden="true" />}
          {actionLabel}
        </button>
      )}
    </div>
  );
}
