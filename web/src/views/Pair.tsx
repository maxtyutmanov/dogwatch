import { useState } from "preact/hooks";
import { PawIcon } from "../PawIcon";

interface Props {
  initialError: string | null;
  onPair: (token: string) => Promise<void>;
  describeError: (e: unknown) => string;
}

export function PairView({ initialError, onPair, describeError }: Props) {
  const [token, setToken] = useState("");
  const [error, setError] = useState<string | null>(initialError);
  const [busy, setBusy] = useState(false);

  const submit = async (e: Event) => {
    e.preventDefault();
    // Accept either the bare token or the whole pairing link.
    const value = token.trim().replace(/^.*#pair=/, "");
    if (!value) return;
    setBusy(true);
    setError(null);
    try {
      await onPair(decodeURIComponent(value));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div class="pair">
      <div class="pair-card">
        <div class="pair-logo">
          <PawIcon />
        </div>
        <h1>Pair this browser</h1>
        <ol class="steps">
          <li>Open the <b>Dogwatch</b> app on the home phone.</li>
          <li>Scan the QR code it shows with this device's camera.</li>
        </ol>
        {error && (
          <p class="error" role="alert">
            {error}
          </p>
        )}
        <details class="manual">
          <summary>Enter the code by hand instead</summary>
          <form onSubmit={submit}>
            <label for="token">Pairing code or link</label>
            <input
              id="token"
              value={token}
              onInput={(e) => setToken((e.target as HTMLInputElement).value)}
              autocomplete="off"
              autocapitalize="off"
              spellcheck={false}
              placeholder="Paste from the phone's “Copy link”"
            />
            <button type="submit" disabled={busy || !token.trim()}>
              {busy ? "Pairing…" : "Pair"}
            </button>
          </form>
        </details>
      </div>
    </div>
  );
}
