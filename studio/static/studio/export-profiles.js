(() => {
  "use strict";

  // Shared with /api/v1/export-profiles/; the server embeds this JSON once.
  // Publication limits are advice and never truncate the original video.
  const catalog = JSON.parse(document.getElementById("studio-export-catalog").textContent);
  const profiles = Object.freeze(catalog.profiles.map((profile) => Object.freeze({
    ...profile, formats: Object.freeze([...profile.formats]),
  })));
  const ratios = Object.freeze(catalog.ratios);
  const resolutions = Object.freeze(catalog.resolutions);
  const bitrates = catalog.bitrates;

  function dimensions(aspect, resolution) {
    const [horizontal, vertical] = ratios[aspect] || ratios["9:16"];
    const shortSide = resolutions.includes(Number(resolution)) ? Number(resolution) : 720;
    return horizontal > vertical
      ? { width: shortSide * horizontal / vertical, height: shortSide }
      : { width: shortSide, height: shortSide * vertical / horizontal };
  }

  function options({ aspect = "9:16", resolution = 720, quality = "balanced", frameRate = 30 } = {}) {
    const size = dimensions(aspect, resolution);
    // Match the bitrate to the number of output pixels. Portrait and landscape
    // retain the existing quality settings; square and feed exports need less.
    const pixelRatio = size.width * size.height / (Number(resolution) ** 2 * 16 / 9);
    return {
      ...size,
      frameRate: [24, 30].includes(Number(frameRate)) ? Number(frameRate) : 30,
      videoBitsPerSecond: Math.round((bitrates[resolution]?.[quality] || 4000000) * (Number.isFinite(pixelRatio) ? pixelRatio : 1)),
      audioBitsPerSecond: catalog.audioBitrates[quality] || 128000,
    };
  }

  window.StudioExportProfiles = Object.freeze({ profiles, resolutions, dimensions, options });
})();
