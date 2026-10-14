import { createRoot } from "react-dom/client";
import SalesStartPage from "../../src/pages/Admin/SalesStartPage";
import SalesHistoryPage from "../../src/pages/Admin/SalesHistoryPage";
import "../../src/index.css";
export function mountCheckout() { createRoot(document.getElementById("integrity-root")!).render(<SalesStartPage standalone operatorName="Teste" />); }
export function mountHistory() { createRoot(document.getElementById("integrity-root")!).render(<SalesHistoryPage />); }
