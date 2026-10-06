/**
 * Arquivo: DESKTOP/preload.js
 * Objetivo: ponte mínima e segura entre a página do PDV e o programa desktop (contextIsolation).
 *           Expõe só o que o PDV precisa — nada de acesso livre a disco ou Node.
 */
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("quackDesktop", {
  /** Grava o backup das pendências (JSON) em Documentos\Quack PDV\Backups. Resolve com o caminho salvo. */
  savePendingBackup: (json) => ipcRenderer.invoke("quack:save-pending-backup", json),

  /** Gateway embutido (roda junto com o programa em 127.0.0.1). Nunca devolve o token. */
  gateway: {
    status: () => ipcRenderer.invoke("quack:gateway-status"),
    /** { token: "qgw_...", companyId, storeId?, apiUrl } — ativa/troca a configuração e reinicia o Gateway. */
    configure: (input) => ipcRenderer.invoke("quack:gateway-configure", input),
    disable: () => ipcRenderer.invoke("quack:gateway-disable"),
  },
});
