import { createRoot } from "react-dom/client";
import { QuickCustomerRegisterModal } from "../../src/components/Admin/QuickCustomerRegisterModal";

/** Monta o componente real para verificar o cadastro rápido sem iniciar uma venda. */
export function mountQuickCustomer() {
  const host = document.createElement("div");
  host.id = "customer-quick-test";
  document.body.appendChild(host);
  createRoot(host).render(<QuickCustomerRegisterModal open onClose={() => {}}
    onSuccess={customer => { host.dataset.saved = customer.id; }} />);
}
