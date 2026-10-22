"use strict";

// 14 navigation paths copied read-only from design/design-data.json lines 21-34.
// Do not edit design/ here.
const PATHS = {
  "left": "M78 20H42V4L6 40l36 36V58h36V20Z",
  "right": "M6 20h36V4l36 36-36 36V58H6V20Z",
  "straight": "M22 78V40H4L40 4l36 36H58v38H22Z",
  "slight-left": "M61 77V49L26 14M8 14h29M8 14v29",
  "slight-right": "M23 77V49l35-35M76 14H47M76 14v29",
  "sharp-left": "M64 77V16L20 58M20 32v26h26",
  "sharp-right": "M20 77V16l44 42M64 32v26H38",
  "keep-left": "M39 77V48L20 29V8M7 21 20 8l13 13M43 46l21-20V8",
  "keep-right": "M45 77V48l19-19V8M51 21 64 8l13 13M41 46 20 26V8",
  "uturn-left": "M64 76V28a20 20 0 0 0-40 0v30M8 43l16 16 16-16",
  "uturn-right": "M20 76V28a20 20 0 0 1 40 0v30M44 43l16 16 16-16",
  "roundabout-enter": "M42 78V64a23 23 0 1 1 23-23M53 30l12 12 12-12",
  "roundabout-exit": "M42 78V64a23 23 0 1 1 20-35l14-15M57 14h19v19",
  "arrive": "M18 76V9h46v37H18M19 10l44 35M63 10 19 45"
};

module.exports = { PATHS };
