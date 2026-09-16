import { type ReactNode, createContext, useCallback, useContext, useRef, useState } from "react";

const AnnouncerContext = createContext<((message: string) => void) | null>(null);

/** A single shared aria-live region for announcing the result of async actions to screen readers. */
export function AnnouncerProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState("");
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const announce = useCallback((next: string) => {
    // Clear then set on a tick so the same message announced twice in a row is still read aloud.
    setMessage("");
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    timeoutRef.current = setTimeout(() => setMessage(next), 50);
  }, []);

  return (
    <AnnouncerContext.Provider value={announce}>
      {children}
      <div aria-live="polite" role="status" className="visually-hidden">
        {message}
      </div>
    </AnnouncerContext.Provider>
  );
}

export function useAnnounce(): (message: string) => void {
  const ctx = useContext(AnnouncerContext);
  if (!ctx) throw new Error("useAnnounce must be used within AnnouncerProvider");
  return ctx;
}
