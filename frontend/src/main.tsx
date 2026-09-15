import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { initTheme } from "./theme";
import "./styles.css";
import "./theme-parchment.css";

// Before the first paint, so the page never renders in one skin and swaps to the other.
initTheme();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
