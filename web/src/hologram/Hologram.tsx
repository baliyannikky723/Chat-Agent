import { useEffect, useRef } from 'react';
import {
  AdditiveBlending,
  BufferGeometry,
  CanvasTexture,
  CircleGeometry,
  Color,
  CylinderGeometry,
  DoubleSide,
  Float32BufferAttribute,
  FrontSide,
  Group,
  LineBasicMaterial,
  LineSegments,
  Mesh,
  MeshBasicMaterial,
  PerspectiveCamera,
  Points,
  PointsMaterial,
  Scene,
  ShaderMaterial,
  SphereGeometry,
  Sprite,
  SpriteMaterial,
  SRGBColorSpace,
  TorusGeometry,
  Vector3,
  WebGLRenderer,
  type IUniform,
  type Side,
  type Texture,
} from 'three';

/**
 * The hologram: a projector disc casting a beam, a core (now) inside three orbits, and
 * brass planets on the outer orbit, one for each day Orrery has conversations from.
 * Its mood follows the app: idle, listening while you type, busy while it works.
 *
 * Plain three.js in one effect: a single scene does not need a React renderer, and named
 * imports let the bundler drop the parts of three.js that are not used. Custom shaders
 * write sRGB values directly (plain vectors), so colours match the CSS palette exactly.
 */

export type HoloMood = 'idle' | 'listening' | 'busy';

const srgb = (hex: string) => {
  const n = parseInt(hex.slice(1), 16);
  return new Vector3(((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255);
};
const CYAN = '#7fe3f0';
const LILAC = '#b7a6ff';
const BRASS = '#d9b36c';

const MOODS: Record<HoloMood, { spin: number; flow: number; scan: number; glow: number }> = {
  idle: { spin: 0.22, flow: 0.08, scan: 1.1, glow: 0.9 },
  listening: { spin: 0.55, flow: 0.38, scan: 2.3, glow: 1.08 },
  busy: { spin: 1.25, flow: 1.1, scan: 3.6, glow: 1.2 },
};

const easeOut = (x: number) => 1 - Math.pow(1 - Math.min(1, Math.max(0, x)), 3);

const passUv = /* glsl */ `
  varying vec2 vUv;
  void main() {
    vUv = uv;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

const coreVertex = /* glsl */ `
  varying vec3 vNormal;
  varying vec3 vView;
  varying vec3 vPos;
  void main() {
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    vView = normalize(-mv.xyz);
    vNormal = normalize(normalMatrix * normal);
    vPos = position;
    gl_Position = projectionMatrix * mv;
  }
`;

const coreFragment = /* glsl */ `
  uniform float uTime;
  uniform float uScan;
  uniform float uGlow;
  uniform float uReveal;
  uniform vec3 uColorA;
  uniform vec3 uColorB;
  varying vec3 vNormal;
  varying vec3 vView;
  varying vec3 vPos;
  void main() {
    float fresnel = pow(1.0 - max(dot(vNormal, vView), 0.0), 2.0);
    float bands = 0.5 + 0.5 * sin(vPos.y * 46.0 - uTime * uScan * 2.0);
    float flicker = 0.94 + 0.06 * sin(uTime * 21.0) * sin(uTime * 5.3);
    vec3 color = mix(uColorA, uColorB, clamp(0.55 - vPos.y * 1.3, 0.0, 1.0));
    float alpha = (0.1 + fresnel * 0.95 + bands * 0.14) * uGlow * flicker * uReveal;
    gl_FragColor = vec4(color * (0.55 + fresnel * 0.9), alpha);
  }
`;

const ringFragment = /* glsl */ `
  uniform float uTime;
  uniform float uFlow;
  uniform float uDashes;
  uniform float uReveal;
  uniform vec3 uColor;
  varying vec2 vUv;
  void main() {
    if (vUv.x > uReveal) discard;
    float d = fract(vUv.x * uDashes - uTime * uFlow);
    float dash = smoothstep(0.0, 0.06, d) * (1.0 - smoothstep(0.52, 0.6, d));
    gl_FragColor = vec4(uColor, (0.24 + dash * 0.76) * 0.9);
  }
`;

const beamFragment = /* glsl */ `
  uniform float uTime;
  uniform float uGlow;
  uniform float uReveal;
  uniform vec3 uColor;
  varying vec2 vUv;
  void main() {
    float rise = smoothstep(uReveal, uReveal - 0.25, vUv.y);
    float fade = pow(1.0 - vUv.y, 1.7);
    float streaks = 0.72 + 0.28 * sin(vUv.x * 140.0 + uTime * 0.7) * sin(vUv.x * 41.0 - uTime * 0.45);
    gl_FragColor = vec4(uColor, fade * streaks * 0.2 * uGlow * rise);
  }
`;

const discFragment = /* glsl */ `
  uniform float uGlow;
  uniform vec3 uColor;
  varying vec2 vUv;
  void main() {
    float d = distance(vUv, vec2(0.5)) * 2.0;
    float pool = (1.0 - smoothstep(0.0, 1.0, d)) * 0.22;
    float rim = smoothstep(0.86, 0.95, d) * (1.0 - smoothstep(0.95, 1.0, d)) * 0.55;
    float lens = (1.0 - smoothstep(0.0, 0.32, d)) * 0.35;
    gl_FragColor = vec4(uColor, (pool + rim + lens) * uGlow);
  }
`;

function glowTexture(): Texture {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 64;
  const g = canvas.getContext('2d')!;
  const gradient = g.createRadialGradient(32, 32, 0, 32, 32, 32);
  gradient.addColorStop(0, 'rgba(255,255,255,1)');
  gradient.addColorStop(0.22, 'rgba(255,255,255,0.55)');
  gradient.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = gradient;
  g.fillRect(0, 0, 64, 64);
  const texture = new CanvasTexture(canvas);
  texture.colorSpace = SRGBColorSpace;
  return texture;
}

function shader(fragmentShader: string, vertexShader: string, uniforms: Record<string, IUniform>, side: Side = FrontSide) {
  return new ShaderMaterial({ uniforms, vertexShader, fragmentShader, transparent: true, depthWrite: false, blending: AdditiveBlending, side });
}

interface Ring {
  radius: number;
  tilt: [number, number];
  color: string;
  dashes: number;
  speed: number;
  planets: number;
  delay: number;
}

/** Builds the scene; returns a per-frame update and a disposer. */
function buildScene(scene: Scene, planets: number) {
  const disposables: { dispose(): void }[] = [];
  const keep = <T extends { dispose(): void }>(x: T): T => {
    disposables.push(x);
    return x;
  };
  const glow = keep(glowTexture());

  // Faint stars far behind.
  const stars: number[] = [];
  let seed = 7;
  const random = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  for (let i = 0; i < 520; i++) {
    const u = random() * 2 - 1;
    const phi = random() * Math.PI * 2;
    const r = 5 + random() * 4;
    const s = Math.sqrt(1 - u * u);
    stars.push(Math.cos(phi) * s * r, u * r * 0.6, Math.sin(phi) * s * r - 2);
  }
  const starGeometry = keep(new BufferGeometry());
  starGeometry.setAttribute('position', new Float32BufferAttribute(stars, 3));
  scene.add(new Points(starGeometry, keep(new PointsMaterial({ color: new Color('#9aa6c7'), size: 0.035, transparent: true, opacity: 0.7, depthWrite: false }))));

  const root = new Group();
  scene.add(root);
  const center = new Group();
  center.position.set(0, 0.16, 0);
  root.add(center);

  const core = keep(
    shader(coreFragment, coreVertex, {
      uTime: { value: 0 },
      uScan: { value: 1 },
      uGlow: { value: 1 },
      uReveal: { value: 0 },
      uColorA: { value: srgb(CYAN) },
      uColorB: { value: srgb(LILAC) },
    }),
  );
  center.add(new Mesh(keep(new SphereGeometry(0.34, 64, 48)), core));
  center.add(new Mesh(keep(new SphereGeometry(0.12, 32, 24)), keep(new MeshBasicMaterial({ color: new Color('#f4fdff') }))));
  const halo = new Sprite(keep(new SpriteMaterial({ map: glow, color: new Color(CYAN), blending: AdditiveBlending, depthWrite: false, transparent: true, opacity: 0.55 })));
  halo.scale.set(1.6, 1.6, 1);
  center.add(halo);

  const rings: Ring[] = [
    { radius: 0.74, tilt: [Math.PI / 2 - 0.42, 0.25], color: CYAN, dashes: 26, speed: 1.0, planets: 1, delay: 0.25 },
    { radius: 1.08, tilt: [Math.PI / 2 + 0.3, -0.38], color: LILAC, dashes: 34, speed: 0.62, planets: 2, delay: 0.42 },
    { radius: 1.44, tilt: [Math.PI / 2 - 0.14, 0.5], color: BRASS, dashes: 44, speed: 0.38, planets: Math.min(7, Math.max(0, planets)), delay: 0.58 },
  ];
  const planetGeometry = keep(new SphereGeometry(0.052, 20, 16));
  const ringParts = rings.map((ring, i) => {
    const tilt = new Group();
    tilt.rotation.set(ring.tilt[0], 0, ring.tilt[1]);
    const spin = new Group();
    tilt.add(spin);
    center.add(tilt);
    const material = keep(
      shader(ringFragment, passUv, { uTime: { value: 0 }, uFlow: { value: 0.1 }, uDashes: { value: ring.dashes }, uReveal: { value: 0 }, uColor: { value: srgb(ring.color) } }, DoubleSide),
    );
    spin.add(new Mesh(keep(new TorusGeometry(ring.radius, 0.0065, 8, 260)), material));
    const planetMaterial = keep(new MeshBasicMaterial({ color: new Color(ring.color) }));
    const spriteMaterial = keep(new SpriteMaterial({ map: glow, color: new Color(ring.color), blending: AdditiveBlending, depthWrite: false, transparent: true, opacity: 0.85 }));
    for (let p = 0; p < ring.planets; p++) {
      const a = (p / Math.max(1, ring.planets)) * Math.PI * 2 + i * 1.3 + p * 0.17;
      const holder = new Group();
      holder.position.set(Math.cos(a) * ring.radius, Math.sin(a) * ring.radius, 0);
      holder.add(new Mesh(planetGeometry, planetMaterial));
      const sprite = new Sprite(spriteMaterial);
      sprite.scale.set(0.34, 0.34, 1);
      holder.add(sprite);
      spin.add(holder);
    }
    return { ring, spin, material };
  });

  // The projector: a beam rising from a dial of 120 ticks, longer every 30 degrees.
  const beam = keep(shader(beamFragment, passUv, { uTime: { value: 0 }, uGlow: { value: 1 }, uReveal: { value: 0 }, uColor: { value: srgb(CYAN) } }, DoubleSide));
  root.add(new Mesh(keep(new CylinderGeometry(1.2, 0.5, 2.3, 72, 1, true)), beam));
  const base = new Group();
  base.position.set(0, -1.15, 0);
  root.add(base);
  const disc = keep(shader(discFragment, passUv, { uGlow: { value: 1 }, uColor: { value: srgb(CYAN) } }));
  const discMesh = new Mesh(keep(new CircleGeometry(1.36, 96)), disc);
  discMesh.rotation.x = -Math.PI / 2;
  base.add(discMesh);
  const ticks: number[] = [];
  for (let i = 0; i < 120; i++) {
    const a = (i / 120) * Math.PI * 2;
    const inner = i % 10 === 0 ? 1.18 : 1.27;
    ticks.push(Math.cos(a) * inner, 0, Math.sin(a) * inner, Math.cos(a) * 1.34, 0, Math.sin(a) * 1.34);
  }
  const tickGeometry = keep(new BufferGeometry());
  tickGeometry.setAttribute('position', new Float32BufferAttribute(ticks, 3));
  base.add(new LineSegments(tickGeometry, keep(new LineBasicMaterial({ color: new Color(CYAN), transparent: true, opacity: 0.4, blending: AdditiveBlending, depthWrite: false }))));

  const state = { t: 0, boot: 0, ...MOODS.idle, px: 0, py: 0 };

  function update(dt: number, mood: HoloMood, pointer: { x: number; y: number }) {
    const target = MOODS[mood];
    const k = 1 - Math.exp(-dt * 2.5);
    state.spin += (target.spin - state.spin) * k;
    state.flow += (target.flow - state.flow) * k;
    state.scan += (target.scan - state.scan) * k;
    state.glow += (target.glow - state.glow) * k;
    state.t += dt;
    state.boot += dt;

    // The one orchestrated moment: the beam rises, the core lights, each orbit draws itself.
    const beamReveal = easeOut(state.boot / 0.9);
    beam.uniforms.uReveal.value = beamReveal * 1.25;
    beam.uniforms.uTime.value = state.t;
    beam.uniforms.uGlow.value = state.glow;
    disc.uniforms.uGlow.value = state.glow * (0.4 + 0.6 * beamReveal);
    core.uniforms.uTime.value = state.t;
    core.uniforms.uScan.value = state.scan;
    core.uniforms.uGlow.value = state.glow;
    core.uniforms.uReveal.value = easeOut((state.boot - 0.15) / 0.7);
    ringParts.forEach(({ ring, spin, material }, i) => {
      const reveal = easeOut((state.boot - ring.delay) / 0.9);
      material.uniforms.uTime.value = state.t;
      material.uniforms.uFlow.value = state.flow * (1.2 - i * 0.25);
      material.uniforms.uReveal.value = reveal * 1.001;
      spin.rotation.z += dt * state.spin * ring.speed;
      spin.scale.setScalar(0.8 + 0.2 * reveal);
    });

    // Gentle parallax toward the pointer, and a slow breath.
    state.px += (pointer.x * 0.22 - state.px) * k;
    state.py += (pointer.y * 0.08 - state.py) * k;
    root.rotation.set(-state.py, state.px, 0);
    center.scale.setScalar(1 + Math.sin(state.t * 1.6) * 0.012 * state.glow);
  }

  return {
    update,
    skipIntro: () => {
      state.boot = 10;
    },
    dispose: () => disposables.forEach((d) => d.dispose()),
  };
}

export default function Hologram({ mood = 'idle', planets = 0, dim = false }: { mood?: HoloMood; planets?: number; dim?: boolean }) {
  const host = useRef<HTMLDivElement>(null);
  const moodRef = useRef(mood);
  moodRef.current = mood;

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const renderer = new WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75));
    renderer.setClearColor(0x000000, 0);
    renderer.domElement.style.display = 'block';
    renderer.domElement.setAttribute('aria-hidden', 'true');
    el.appendChild(renderer.domElement);

    const scene = new Scene();
    // Framed so the whole projector dial stays in view on wide and narrow screens.
    const camera = new PerspectiveCamera(38, 1, 0.1, 40);
    camera.position.set(0, 0.85, 5.0);
    camera.lookAt(0, -0.12, 0);
    const world = buildScene(scene, planets);
    if (reduced) world.skipIntro();

    const resize = () => {
      const w = Math.max(1, el.clientWidth);
      const h = Math.max(1, el.clientHeight);
      renderer.setSize(w, h, false);
      renderer.domElement.style.width = `${w}px`;
      renderer.domElement.style.height = `${h}px`;
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      if (reduced) {
        world.update(0.016, moodRef.current, { x: 0, y: 0 });
        renderer.render(scene, camera);
      }
    };
    const observer = new ResizeObserver(resize);
    observer.observe(el);
    resize();

    const pointer = { x: 0, y: 0 };
    const onPointer = (e: PointerEvent) => {
      pointer.x = (e.clientX / window.innerWidth) * 2 - 1;
      pointer.y = -((e.clientY / window.innerHeight) * 2 - 1);
    };
    window.addEventListener('pointermove', onPointer, { passive: true });

    // With reduced motion the scene is drawn once, still. Hidden tabs pause rAF on their own.
    let frame = 0;
    let last = performance.now();
    const loop = (now: number) => {
      frame = requestAnimationFrame(loop);
      const dt = Math.min((now - last) / 1000, 0.05);
      last = now;
      world.update(dt, moodRef.current, pointer);
      renderer.render(scene, camera);
    };
    if (!reduced) frame = requestAnimationFrame(loop);

    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
      window.removeEventListener('pointermove', onPointer);
      world.dispose();
      renderer.dispose();
      renderer.domElement.remove();
    };
  }, [planets]);

  return <div ref={host} className="h-full w-full" style={{ opacity: dim ? 0.6 : 1 }} />;
}
