export interface ElectronAPI {
  minimizeWindow: () => Promise<void>
  maximizeWindow: () => Promise<void>
  closeWindow: () => Promise<void>
  openExternal: (url: string) => Promise<void>
  getVersion: () => Promise<string>
  platform: string
  onBackendReady: (callback: () => void) => () => void
}
