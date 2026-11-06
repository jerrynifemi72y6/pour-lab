# Pour Lab

A fluid sandbox for your thumb. Four materials share one particle engine: water,
sand, bubbles and honey. Tilt the phone to pour, drag the cup around, and watch the
liquid pool on the floor and fill the glass.

## How it works

- `FluidView` - ~340 particles, three substeps per frame, spatial-hash neighbours.
  Each substep applies gravity, damping, a velocity cap, then pairwise separation
  with viscosity blending and a weak cohesion pull, then collision.
- Vessels are three line segments (left wall, right wall, floor). Segment collision
  is two-sided, so particles outside a wall get pushed out, and the open top lets
  anything above the rim spill.
- Gravity comes from the accelerometer in screen space, so tilting the phone is
  physically the same as tilting a real cup. Bubbles run the gravity sign backwards.
- Rendering is per-material: soft additive blobs sharing one radial shader (moved with
  a reusable Matrix, so no per-frame allocations), grains for sand, rings for bubbles.

## Why one engine

Cohesion, viscosity, restitution and gravity scale are the only knobs that differ
between water and sand. Sand just has almost no cohesion, so it piles at an angle;
honey has high cohesion and high viscosity, so it ropes.

## Build

```
gradle assembleRelease
```

CI publishes a signed APK on every push to `main`, using the committed key in
`keystore/` so updates install over the previous version.
