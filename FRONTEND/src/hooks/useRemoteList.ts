import { useCallback, useEffect, useRef, useState } from "react";

/** Distingue lista vazia de falha e permite recarregar sem perder alterações locais. */
export default function useRemoteList<T>(load: () => Promise<T[]>, errorMessage: string) {
  const [items, setItems] = useState<T[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestVersion = useRef(0);
  const reload = useCallback(async () => {
    const version = ++requestVersion.current;
    setIsLoading(true);
    setError(null);
    try {
      const result = await load();
      if (version === requestVersion.current) setItems(result);
    } catch {
      if (version === requestVersion.current) setError(errorMessage);
    } finally {
      if (version === requestVersion.current) setIsLoading(false);
    }
  }, [load, errorMessage]);
  useEffect(() => {
    void reload();
    return () => { requestVersion.current += 1; };
  }, [reload]);
  return { items, setItems, isLoading, error, reload };
}
