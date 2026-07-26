// Static imports verify that the barrel file and its transitive dependencies
// resolve without errors. In a test environment (no native runtime), the
// native module itself resolves to null — that's the documented contract.
import '../index';
import SonyCameraView from '../SonyCameraView';
import SonyCameraViewWeb from '../SonyCameraView.web';

describe('expo-sony-camera module exports', () => {
  it('resolves the barrel export without errors', () => {
    // The static import at the top of this file is the test.
    expect(true).toBe(true);
  });

  it('exports SonyCameraView as a function component', () => {
    expect(SonyCameraView).toBeDefined();
    expect(typeof SonyCameraView).toBe('function');
  });

  it('exports a null SonyCameraView.web fallback', () => {
    expect(SonyCameraViewWeb).toBeDefined();
    // The web fallback returns null at render time (it IS a function
    // component, not a null export). The native view wraps requireNativeView,
    // which also returns a component whose rendering depends on the runtime.
    expect(typeof SonyCameraViewWeb).toBe('function');
  });

  it('has the expected native-module null guard', () => {
    // In a test environment there is no Expo native runtime, so
    // requireOptionalNativeModule returns null. The TypeScript declaration
    // class documents the shape; this test confirms the guard works.
    const SonyCamera = require('../SonyCameraModule').default;
    expect(SonyCamera).toBeNull();
  });

  it('re-exports all public types without runtime errors', () => {
    // Type-only exports produce no runtime values, but a broken re-export
    // (e.g. from a file that doesn't exist or has a syntax error) prevents
    // the module from loading. The static imports at the top already cover
    // this; this test is an explicit assertion that the barrel is healthy.
    const barrel = require('../index');
    expect(barrel).toBeDefined();
  });
});
