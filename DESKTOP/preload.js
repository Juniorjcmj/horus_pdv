/**
 * Arquivo: DESKTOP/preload.js
 * Objetivo: ponte mínima e segura entre a página do PDV e o programa desktop (contextIsolation).
 *           Expõe só o que o PDV precisa — nada de acesso livre a disco ou Node.
 */
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("quackDesktop", {
  /** Grava o backup das pendências (JSON) em Documentos\Quack PDV\Backups. Resolve com o caminho salvo. */
  savePendingBackup: (json) => ipcRenderer.invoke("quack:save-pending-backup", json),
});
