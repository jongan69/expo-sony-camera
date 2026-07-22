// Re-export the native module. On web, it will be resolved to SonyCameraModule.web.ts
// and on native platforms to SonyCameraModule.ts
export { default } from './SonyCameraModule';
export { default as SonyCameraView } from './SonyCameraView';
export * from './SonyCamera.types';
