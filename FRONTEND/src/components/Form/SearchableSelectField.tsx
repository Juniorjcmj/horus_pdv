/**
 * Arquivo: src/components/Form/SearchableSelectField.tsx
 * Objetivo: padronizar seleção pesquisável para listas de entidades do sistema.
  * Entradas esperadas: recebe lista de opções, valor atual, callbacks de seleção e configuração de criação rápida.
*/
import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Plus, Search } from "lucide-react";
import { createPortal } from "react-dom";

type SearchableSelectFieldProps<TOption> = {
  label?: string;
  value: string;
  options: TOption[];
  onChange: (value: string, option: TOption | null) => void;
  getOptionValue: (option: TOption) => string;
  getOptionLabel: (option: TOption) => string;
  getOptionDescription?: (option: TOption) => string | undefined;
  getOptionSearchText?: (option: TOption) => string;
  placeholder?: string;
  emptyMessage?: string;
  disabled?: boolean;
  required?: boolean;
  className?: string;
  inputClassName?: string;
  dropdownClassName?: string;
  createActionLabel?: string;
  onCreateOption?: (search: string) => void;
};

function normalizeSearchValue(value: string) {
  return String(value || "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}

export default function SearchableSelectField<TOption>({
  label,
  value,
  options,
  onChange,
  getOptionValue,
  getOptionLabel,
  getOptionDescription,
  getOptionSearchText,
  placeholder = "Digite para filtrar",
  emptyMessage = "Nenhum resultado encontrado.",
  disabled = false,
  required = false,
  className = "",
  inputClassName = "",
  dropdownClassName = "",
  createActionLabel,
  onCreateOption,
}: SearchableSelectFieldProps<TOption>) {
  const inputId = useId();
  const listId = `${inputId}-options`;
  const blurTimeoutRef = useRef<number | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [activeIndex, setActiveIndex] = useState(-1);
  const [placement, setPlacement] = useState<{ top: number; left: number; width: number; maxHeight: number } | null>(null);

  const selectedOption = useMemo(
    () => options.find((option) => getOptionValue(option) === value) || null,
    [getOptionValue, options, value],
  );

  const selectedLabel = selectedOption ? getOptionLabel(selectedOption) : "";

  useEffect(() => {
    if (isOpen) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSearch(selectedLabel);
  }, [isOpen, selectedLabel]);

  useEffect(() => {
    inputRef.current?.setCustomValidity(
      required && !value ? "Selecione uma opção da lista." : "",
    );
  }, [required, value]);

  useEffect(
    () => () => {
      if (blurTimeoutRef.current) {
        window.clearTimeout(blurTimeoutRef.current);
      }
    },
    [],
  );

  const filteredOptions = useMemo(() => {
    const normalized = normalizeSearchValue(search);
    if (!normalized) return options;
    return options.filter((option) => {
      const text = getOptionSearchText
        ? getOptionSearchText(option)
        : `${getOptionLabel(option)} ${getOptionDescription?.(option) || ""}`;
      return normalizeSearchValue(text).includes(normalized);
    });
  }, [getOptionDescription, getOptionLabel, getOptionSearchText, options, search]);

  useEffect(() => {
    if (!isOpen || disabled) return;
    const place = () => {
      const rect = inputRef.current?.getBoundingClientRect();
      if (!rect) return;
      const below = window.innerHeight - rect.bottom - 16;
      const above = rect.top - 16;
      const height = Math.min(panelRef.current?.scrollHeight ?? 224, 224);
      const upwards = below < height && above > below;
      const maxHeight = Math.max(44, Math.min(224, upwards ? above : below));
      setPlacement({
        top: upwards ? Math.max(8, rect.top - Math.min(height, maxHeight) - 8) : rect.bottom + 8,
        left: Math.max(8, Math.min(rect.left, window.innerWidth - rect.width - 8)),
        width: Math.min(rect.width, window.innerWidth - 16),
        maxHeight,
      });
    };
    const frame = requestAnimationFrame(place);
    window.addEventListener("resize", place);
    window.addEventListener("scroll", place, true);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("resize", place);
      window.removeEventListener("scroll", place, true);
    };
  }, [isOpen, disabled, filteredOptions.length]);

  useEffect(() => {
    if (!isOpen || activeIndex < 0) return;
    document.getElementById(`${listId}-${activeIndex}`)?.scrollIntoView({ block: "nearest" });
  }, [activeIndex, isOpen, listId]);

  const handleSelect = (option: TOption) => {
    const nextValue = getOptionValue(option);
    onChange(nextValue, option);
    setSearch(getOptionLabel(option));
    setIsOpen(false);
  };

  const handleBlur = () => {
    blurTimeoutRef.current = window.setTimeout(() => {
      setIsOpen(false);
      setSearch(selectedLabel);
    }, 120);
  };

  const handleCreateOption = () => {
    if (!onCreateOption) return;
    onCreateOption(search.trim());
    setIsOpen(false);
  };

  const field = (
    <>
      <div className="relative">
        <Search
          size={15}
          className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-tertiary"
        />
        <input
          ref={inputRef}
          id={inputId}
          value={search}
          role="combobox"
          aria-label={label ? undefined : placeholder}
          aria-autocomplete="list"
          aria-expanded={isOpen && !disabled}
          aria-controls={isOpen ? listId : undefined}
          aria-activedescendant={isOpen && filteredOptions[activeIndex] ? `${listId}-${activeIndex}` : undefined}
          onChange={(event) => {
            setSearch(event.target.value);
            setIsOpen(true);
            setActiveIndex(-1);
            if (!event.target.value.trim() && value) {
              onChange("", null);
            }
          }}
          onFocus={() => {
            if (blurTimeoutRef.current) window.clearTimeout(blurTimeoutRef.current);
            if (!disabled) setIsOpen(true);
          }}
          onBlur={(event) => {
            if (!panelRef.current?.contains(event.relatedTarget as Node)) handleBlur();
          }}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              event.preventDefault();
              event.stopPropagation();
              setIsOpen(false);
              setSearch(selectedLabel);
            } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
              event.preventDefault();
              setIsOpen(true);
              if (filteredOptions.length) setActiveIndex(current => current < 0
                ? (event.key === "ArrowDown" ? 0 : filteredOptions.length - 1)
                : (current + (event.key === "ArrowDown" ? 1 : -1) + filteredOptions.length) % filteredOptions.length);
            } else if (event.key === "Enter" && isOpen) {
              event.preventDefault();
              if (filteredOptions[activeIndex]) handleSelect(filteredOptions[activeIndex]);
              else if (!filteredOptions.length && onCreateOption) handleCreateOption();
            }
          }}
          className={`input-field w-full pl-9 ${inputClassName}`}
          placeholder={placeholder}
          autoComplete="off"
          disabled={disabled}
          required={required}
        />
      </div>

      {isOpen && !disabled && placement ? createPortal(
        <div
          ref={panelRef}
          style={placement}
          className={`fixed z-layer-popover overflow-y-auto rounded-xl border border-border-primary bg-bg-light shadow-lg ${dropdownClassName}`}
          onBlur={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget as Node) && event.relatedTarget !== inputRef.current) handleBlur();
          }}
        >
        <ul
          id={listId}
          role="listbox"
          aria-label={label || placeholder}
        >
          {filteredOptions.length > 0 ? (
            filteredOptions.map((option, index) => {
              const optionValue = getOptionValue(option);
              const description = getOptionDescription?.(option);
              const isSelected = optionValue === value;
              return (
                <li
                  key={optionValue}
                  id={`${listId}-${index}`}
                  role="option"
                  aria-selected={isSelected}
                  className={`min-h-11 cursor-pointer px-3 py-3 text-sm transition hover:bg-hover-light ${
                    index === activeIndex ? "bg-hover-light" : isSelected ? "bg-accent/10" : ""
                  }`}
                  onMouseMove={() => setActiveIndex(index)}
                  onMouseDown={(event) => {
                    event.preventDefault();
                    handleSelect(option);
                  }}
                >
                  <p className="font-medium text-text-primary">
                    {getOptionLabel(option)}
                  </p>
                  {description ? (
                    <p className="text-xs text-text-secondary">{description}</p>
                  ) : null}
                </li>
              );
            })
          ) : (
            <li className="px-3 py-2 text-sm text-text-secondary">
              {emptyMessage}
            </li>
          )}
        </ul>
          {onCreateOption ? (
            <div className="border-t border-border-primary p-1.5">
              <button
                type="button"
                className="flex min-h-11 w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-sm font-semibold text-secondary transition hover:bg-hover-light"
                onMouseDown={(event) => {
                  event.preventDefault();
                }}
                onClick={handleCreateOption}
              >
                <Plus size={14} />
                {createActionLabel || "Cadastrar novo"}
              </button>
            </div>
          ) : null}
        </div>, document.body,
      ) : null}
    </>
  );

  if (!label) {
    return <div className={`relative ${className}`}>{field}</div>;
  }

  return (
    <label className={`relative block ${className}`}>
      <span className="mb-1 block text-sm text-text-secondary">{label}</span>
      {field}
    </label>
  );
}
