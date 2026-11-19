import { Component, lazy, Suspense, useMemo, type ReactNode } from 'react';
import type { HoloMood } from './Hologram';
import { OrreryFallback } from './OrreryFallback';

const Hologram = lazy(() => import('./Hologram'));

function webglAvailable(): boolean {
  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext('webgl2') || canvas.getContext('webgl'));
  } catch {
    return false;
  }
}

/** If the 3D scene fails for any reason (driver, context loss), the still drawing takes its place. */
class FallbackOnError extends Component<{ fallback: ReactNode; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

/**
 * Where the hologram goes on a page. three.js loads lazily, so the first paint never waits
 * for it; until then (or without WebGL) the matching still drawing holds the space.
 */
export function HologramSlot({ mood = 'idle', planets = 0, dim = false, className }: { mood?: HoloMood; planets?: number; dim?: boolean; className?: string }) {
  const canRender = useMemo(webglAvailable, []);
  const still = <OrreryFallback planets={planets} dim={dim} />;
  return (
    <div className={className}>
      {canRender ? (
        <FallbackOnError fallback={still}>
          <Suspense fallback={still}>
            <Hologram mood={mood} planets={planets} dim={dim} />
          </Suspense>
        </FallbackOnError>
      ) : (
        still
      )}
    </div>
  );
}
