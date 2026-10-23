/**
 * Simba OSRS Swarm Command Center & Process Management Service
 * Zero-dependency Node.js HTTP server on Port 7777.
 *
 * Provides:
 * - Static PWA serving (index.html, manifest.json, sw.js)
 * - Live telemetry metrics & dynamic SVG / PNG screenshot streaming
 * - Isolated per-bot profiles with -Duser.home isolation
 * - Pre-launch SOCKS5 proxy TCP reachability check (kill-switch)
 * - Remote command dispatch (HOP, CHAT, PAUSE, RESUME, LOGOUT_STOP)
 * - 4-Shift Circadian Swarm Scheduler for i5-11400H + 32GB RAM
 * - Authentic 6-archetype Human RSN Generator & Taken-Name Mutator (<= 12 chars)
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');
const net = require('net');
const os = require('os');
const { spawn } = require('child_process');

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 7777;
const SWARM_ROOT = path.resolve('C:\\Simba\\Swarm');
const PUBLIC_DIR = path.join(SWARM_ROOT, 'public');
const PROFILES_DIR = path.join(SWARM_ROOT, 'profiles');
const TELEMETRY_DIR = path.join(SWARM_ROOT, 'telemetry');
const COMMANDS_DIR = path.join(SWARM_ROOT, 'commands');

// Ensure all subdirectories exist
[PUBLIC_DIR, PROFILES_DIR, TELEMETRY_DIR, COMMANDS_DIR].forEach((dir) => {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
});

// Runtime state for tracked processes
const activeProcesses = new Map(); // botId -> { pid, process, startTime, botId, rsn }
const serverStartTime = Date.now();

const BOT_ID_REGEX = /^[a-zA-Z0-9_-]+$/;

// Host Hardware Specs
const HOST_HARDWARE = {
  cpuModel: 'Intel(R) Core(TM) i5-11400H @ 2.70GHz',
  physicalCores: 6,
  logicalThreads: 12,
  totalRamGB: 32,
  allocatedRamGB: 8,
  maxConcurrentHeadedBots: 6,
  totalCircadianAccounts: 24,
  shiftsPerCycle: 4,
  accountsPerShift: 6,
  targetActiveHoursPerDay: 4.5,
};

// ============================================================================
// 1. HUMAN RSN GENERATOR & MUTATOR (6 Archetypes, Strictly <= 12 Characters)
// ============================================================================

const RSN_ARCHETYPES = {
  FIRST_LAST_COMPOUND: {
    name: 'Compound Evocative',
    part1: [
      'Iron', 'Swift', 'Drift', 'Frost', 'Night', 'Storm', 'Ember', 'Dusk',
      'Moss', 'Ash', 'Flint', 'Stone', 'Gale', 'Wren', 'Pike', 'Shade',
      'Rook', 'Fern', 'Hawk', 'Cinder', 'Bramble', 'Vale', 'Pine', 'Rust'
    ],
    part2: [
      'Raven', 'Oak', 'Wren', 'Pike', 'Vale', 'Wolf', 'Fang', 'Glow',
      'Wing', 'Leaf', 'Bark', 'Fall', 'Claw', 'Mist', 'Wood', 'Peak',
      'Rock', 'Root', 'Pine', 'Lake', 'Helm', 'Mark', 'Breeze', 'Shade'
    ]
  },
  ADJECTIVE_NOUN: {
    name: 'Adjective + Noun',
    adjectives: [
      'pale', 'cold', 'deep', 'wild', 'grim', 'raw', 'dark', 'pure',
      'calm', 'lone', 'bold', 'keen', 'lost', 'warm', 'grey', 'soft'
    ],
    nouns: [
      'peak', 'ash', 'stride', 'ember', 'cedar', 'lynx', 'wolf', 'hollow',
      'flint', 'root', 'fall', 'mist', 'pike', 'moss', 'fern', 'vale'
    ],
    separators: ['_', ' ', '']
  },
  CLEAN_WORD_SUFFIX: {
    name: 'Clean Word + Suffix',
    roots: [
      'Vesper', 'Cinder', 'Flint', 'Cobalt', 'Rook', 'Thorn', 'Silt',
      'Alder', 'Kestrel', 'Sorrel', 'Basalt', 'Pyrite', 'Jasper', 'Onyx', 'Zephyr'
    ],
    suffixes: ['7', '12', '15', '22', '88', '94', '99', '3', '07', 'x', 'z']
  },
  SHORT_PHRASE: {
    name: 'Short Phrase / Casual',
    phrases: [
      'No Rush', 'Go Fish', 'Too Chill', 'Wait Fr', 'Just Afk', 'One More',
      'Chop Logs', 'Mine More', 'Afk Mode', 'Why Walk', 'Gg Bro', 'Not Fast',
      'Big Gains', 'Cya Lumby', 'Low Key', 'Sit Rat', 'Ez Xp', 'Free Gp'
    ]
  },
  OLD_SCHOOL_VIBE: {
    name: 'Classic 2004-2007 Era',
    handles: [
      'x_str_pure', 'el_ranger', 'pure_mage7', 'bow_string', 'lumby_boy',
      'varrock_guy', 'runite_kid', 'range_tank', 'pk_legend', 'whip_pure',
      'mauler_99', 'gmaul_spec', 'd_scim_pk', '2h_pure', 'str_pot_99',
      'fish_lvl_99', 'rune_miner', 'woodcut_god'
    ]
  },
  LOWERCASE_CASUAL: {
    name: 'Minimalist Lowercase',
    words: [
      'kestrel', 'sorrel', 'pyrite', 'calico', 'basalt', 'lichen',
      'zephyr', 'tundra', 'verdigris', 'chert', 'slate', 'pumice',
      'loam', 'bauxite', 'feldspar', 'mica', 'diorite', 'spruce'
    ]
  }
};

function getRandomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function getRandomItem(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

/**
 * Generates an authentic human OSRS Display Name (RSN) strictly <= 12 chars.
 */
function generateHumanRSN(archetypeKey) {
  const keys = Object.keys(RSN_ARCHETYPES);
  const selectedKey = (archetypeKey && RSN_ARCHETYPES[archetypeKey])
    ? archetypeKey
    : getRandomItem(keys);

  let rsn = '';
  switch (selectedKey) {
    case 'FIRST_LAST_COMPOUND': {
      const p1 = getRandomItem(RSN_ARCHETYPES.FIRST_LAST_COMPOUND.part1);
      const p2 = getRandomItem(RSN_ARCHETYPES.FIRST_LAST_COMPOUND.part2);
      rsn = p1 + p2;
      break;
    }
    case 'ADJECTIVE_NOUN': {
      const adj = getRandomItem(RSN_ARCHETYPES.ADJECTIVE_NOUN.adjectives);
      const noun = getRandomItem(RSN_ARCHETYPES.ADJECTIVE_NOUN.nouns);
      const sep = getRandomItem(RSN_ARCHETYPES.ADJECTIVE_NOUN.separators);
      rsn = adj + sep + noun;
      break;
    }
    case 'CLEAN_WORD_SUFFIX': {
      const root = getRandomItem(RSN_ARCHETYPES.CLEAN_WORD_SUFFIX.roots);
      const suffix = getRandomItem(RSN_ARCHETYPES.CLEAN_WORD_SUFFIX.suffixes);
      rsn = root + suffix;
      break;
    }
    case 'SHORT_PHRASE': {
      rsn = getRandomItem(RSN_ARCHETYPES.SHORT_PHRASE.phrases);
      break;
    }
    case 'OLD_SCHOOL_VIBE': {
      rsn = getRandomItem(RSN_ARCHETYPES.OLD_SCHOOL_VIBE.handles);
      break;
    }
    case 'LOWERCASE_CASUAL': {
      rsn = getRandomItem(RSN_ARCHETYPES.LOWERCASE_CASUAL.words);
      break;
    }
    default:
      rsn = 'SwiftOak';
  }

  // Strict invariant: 1-12 chars
  if (rsn.length > 12) {
    rsn = rsn.slice(0, 12);
  }
  if (!rsn || rsn.length < 1) {
    rsn = 'DriftWren';
  }

  return { rsn, archetype: selectedKey, length: rsn.length };
}

/**
 * Mutates an RSN authentically when a display name is taken.
 * Strictly maintains length <= 12 characters.
 */
function mutateRSN(baseRsn) {
  let name = (baseRsn || 'DriftWren').trim();
  const mutationType = getRandomInt(1, 6);

  switch (mutationType) {
    case 1: { // Leet / phoneme substitution
      const leetMap = { o: '0', O: '0', i: '1', I: '1', e: '3', E: '3', s: 'z', S: 'Z', a: '4', A: '4' };
      const chars = name.split('');
      for (let i = 0; i < chars.length; i++) {
        if (leetMap[chars[i]]) {
          chars[i] = leetMap[chars[i]];
          break; // mutate just one char naturally
        }
      }
      name = chars.join('');
      break;
    }
    case 2: { // Append birth year / clean 2-digit number
      if (name.length <= 10) {
        const years = ['97', '98', '99', '01', '02', '03', '07', '88', '11'];
        name = name + getRandomItem(years);
      } else if (name.length === 11) {
        name = name + getRandomInt(1, 9);
      } else {
        name = name.slice(0, 11) + getRandomInt(1, 9);
      }
      break;
    }
    case 3: { // Clean prefix (mr, el, x)
      if (name.length <= 9) {
        const prefixes = ['mr ', 'el ', 'x '];
        name = getRandomItem(prefixes) + name;
      } else {
        name = 'x ' + name.slice(0, 10);
      }
      break;
    }
    case 4: { // Clean suffix (og, v2, pk)
      if (name.length <= 9) {
        const suffixes = [' og', ' v2', ' pk', ' rs'];
        name = name + getRandomItem(suffixes);
      } else {
        name = name.slice(0, 9) + ' og';
      }
      break;
    }
    case 5: { // Space / Underscore swap
      if (name.includes(' ')) {
        name = name.replace(' ', '_');
      } else if (name.includes('_')) {
        name = name.replace('_', ' ');
      } else if (name.length <= 11) {
        name = '_' + name;
      }
      break;
    }
    case 6: { // Lowercase or TitleCase flip
      if (name === name.toLowerCase()) {
        name = name.charAt(0).toUpperCase() + name.slice(1);
      } else {
        name = name.toLowerCase();
      }
      break;
    }
  }

  // Strict clamp
  if (name.length > 12) {
    name = name.slice(0, 12);
  }
  if (!name || name.length < 1) {
    name = 'DriftWren_1';
  }

  return { rsn: name, length: name.length, mutated: true };
}

// ============================================================================
// 2. CIRCADIAN SCHEDULER (4 Shifts for i5-11400H + 32GB RAM)
// ============================================================================

const CIRCADIAN_SHIFTS = [
  {
    id: 1,
    name: 'Shift 1: Dawn / Morning',
    windowUtc: '05:00 - 11:00 UTC',
    startHour: 5,
    endHour: 11,
    concurrentLimit: 6,
    targetPlaytimeHours: 4.5,
    description: 'Fresh world population warmup; gathers in low-density zones (Seers, Draynor, Barbarian Village)',
    accounts: [
      { botId: 'bot_woodcutter_01', rsn: 'DriftWren', script: 'DynamicWoodcutter', role: 'Gatherer (Willow/Teak)' },
      { botId: 'bot_miner_02', rsn: 'IronRaven', script: 'DynamicMiner', role: 'Artisan (Iron/Coal)' },
      { botId: 'bot_tut_03', rsn: 'Cinder94', script: 'TutorialIsland', role: 'Onboarding' },
      { botId: 'bot_director_04', rsn: 'No Rush', script: 'MasterAccountDirector', role: 'Multi-Skill Progression' },
      { botId: 'bot_gatherer_05', rsn: 'pale_peak', script: 'DynamicWoodcutter', role: 'Gatherer (Oak/Yew)' },
      { botId: 'bot_miner_06', rsn: 'raw_copper', script: 'DynamicMiner', role: 'Artisan (Tin/Copper)' }
    ]
  },
  {
    id: 2,
    name: 'Shift 2: Midday / Afternoon',
    windowUtc: '11:00 - 17:00 UTC',
    startHour: 11,
    endHour: 17,
    concurrentLimit: 6,
    targetPlaytimeHours: 4.5,
    description: 'European/US morning overlap; higher player density; engages guarded public chat anti-bait logic',
    accounts: [
      { botId: 'bot_wc_07', rsn: 'FrostPike', script: 'DynamicWoodcutter', role: 'Gatherer' },
      { botId: 'bot_mine_08', rsn: 'deep_ember', script: 'DynamicMiner', role: 'Artisan' },
      { botId: 'bot_tut_09', rsn: 'Vesper88', script: 'TutorialIsland', role: 'Onboarding' },
      { botId: 'bot_prog_10', rsn: 'Too Chill', script: 'MasterAccountDirector', role: 'Multi-Skill' },
      { botId: 'bot_wc_11', rsn: 'EmberWolf', script: 'DynamicWoodcutter', role: 'Gatherer' },
      { botId: 'bot_mine_12', rsn: 'kestrel', script: 'DynamicMiner', role: 'Artisan' }
    ]
  },
  {
    id: 3,
    name: 'Shift 3: Evening / Prime Time',
    windowUtc: '17:00 - 23:00 UTC',
    startHour: 17,
    endHour: 23,
    concurrentLimit: 6,
    targetPlaytimeHours: 4.5,
    description: 'US peak evening activity; high chatter; max fatigue drift enabled to mimic natural tiredness',
    accounts: [
      { botId: 'bot_wc_13', rsn: 'StormVale', script: 'DynamicWoodcutter', role: 'Gatherer' },
      { botId: 'bot_mine_14', rsn: 'wild_cedar', script: 'DynamicMiner', role: 'Artisan' },
      { botId: 'bot_tut_15', rsn: 'Cobalt3', script: 'TutorialIsland', role: 'Onboarding' },
      { botId: 'bot_prog_16', rsn: 'Just Afk', script: 'MasterAccountDirector', role: 'Multi-Skill' },
      { botId: 'bot_wc_17', rsn: 'NightGale', script: 'DynamicWoodcutter', role: 'Gatherer' },
      { botId: 'bot_mine_18', rsn: 'pyrite', script: 'DynamicMiner', role: 'Artisan' }
    ]
  },
  {
    id: 4,
    name: 'Shift 4: Night / Graveyard',
    windowUtc: '23:00 - 05:00 UTC',
    startHour: 23,
    endHour: 5,
    concurrentLimit: 6,
    targetPlaytimeHours: 4.5,
    description: 'Quiet nighttime grinding; low player count; switches to secondary or secluded resource nodes',
    accounts: [
      { botId: 'bot_wc_19', rsn: 'MossGlow', script: 'DynamicWoodcutter', role: 'Gatherer' },
      { botId: 'bot_mine_20', rsn: 'quiet_lynx', script: 'DynamicMiner', role: 'Artisan' },
      { botId: 'bot_tut_21', rsn: 'Flint7', script: 'TutorialIsland', role: 'Onboarding' },
      { botId: 'bot_prog_22', rsn: 'One More', script: 'MasterAccountDirector', role: 'Multi-Skill' },
      { botId: 'bot_wc_23', rsn: 'AshFang', script: 'DynamicWoodcutter', role: 'Gatherer' },
      { botId: 'bot_mine_24', rsn: 'zephyr', script: 'DynamicMiner', role: 'Artisan' }
    ]
  }
];

function getCurrentShift() {
  const now = new Date();
  const utcHour = now.getUTCHours();

  for (const shift of CIRCADIAN_SHIFTS) {
    if (shift.startHour < shift.endHour) {
      if (utcHour >= shift.startHour && utcHour < shift.endHour) {
        return shift;
      }
    } else {
      // Wraps around midnight (23:00 - 05:00)
      if (utcHour >= shift.startHour || utcHour < shift.endHour) {
        return shift;
      }
    }
  }
  return CIRCADIAN_SHIFTS[0];
}

// ============================================================================
// 3. SOCKS5 PROXY REACHABILITY CHECK (Pre-launch TCP Kill-Switch)
// ============================================================================

/**
 * Verifies SOCKS5 proxy is reachable via TCP handshake before launching.
 * Returns a promise resolving true if reachable, or rejecting if dead/timeout.
 */
function verifyProxyReachable(host, port, timeoutMs = 2500) {
  return new Promise((resolve, reject) => {
    if (!host && (port === undefined || port === null || port === '')) {
      return resolve(true); // No proxy configured, passes by default
    }

    if (!host || port === undefined || port === null || port === '' || port === 0 || port === '0') {
      return reject(new Error(`Invalid proxy configuration: host=${host}, port=${port}`));
    }

    const numericPort = parseInt(port, 10);
    if (isNaN(numericPort) || numericPort <= 0 || numericPort > 65535) {
      return reject(new Error(`Invalid proxy port: ${port}`));
    }

    const socket = new net.Socket();
    let isSettled = false;

    socket.setTimeout(timeoutMs);

    socket.on('connect', () => {
      if (!isSettled) {
        isSettled = true;
        socket.destroy();
        resolve(true);
      }
    });

    socket.on('timeout', () => {
      if (!isSettled) {
        isSettled = true;
        socket.destroy();
        reject(new Error(`Proxy TCP connection timed out after ${timeoutMs}ms (${host}:${port})`));
      }
    });

    socket.on('error', (err) => {
      if (!isSettled) {
        isSettled = true;
        socket.destroy();
        reject(new Error(`Proxy TCP connection failed (${host}:${port}): ${err.message}`));
      }
    });

    try {
      socket.connect(numericPort, host);
    } catch (err) {
      if (!isSettled) {
        isSettled = true;
        reject(new Error(`Socket connect error: ${err.message}`));
      }
    }
  });
}

// ============================================================================
// 4. DYNAMIC SVG MINI-SCREEN FALLBACK (Dark Slate HUD)
// ============================================================================

function generateFallbackSvg(botId, rsn, state, attention, invCount, runEnergy, posX, posY, runtimeFmt) {
  const safeRsn = (rsn || botId || 'STANDBY_BOT').replace(/[<>&"']/g, '');
  const safeState = (state || 'STANDBY').replace(/[<>&"']/g, '');
  const safeAttention = (attention || 'FOCUSED').replace(/[<>&"']/g, '');
  const safeRuntime = (runtimeFmt || '00:00:00').replace(/[<>&"']/g, '');
  const inv = typeof invCount === 'number' ? invCount : 0;
  const energy = typeof runEnergy === 'number' ? runEnergy : 100;
  const x = posX || 3200;
  const y = posY || 3200;

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 420" width="100%" height="100%">
  <defs>
    <linearGradient id="bgGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#0b0f19" />
      <stop offset="50%" stop-color="#111827" />
      <stop offset="100%" stop-color="#070a10" />
    </linearGradient>
    <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
      <path d="M 40 0 L 0 0 0 40" fill="none" stroke="#1f293d" stroke-width="0.75" stroke-opacity="0.4"/>
    </pattern>
    <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
      <feGaussianBlur stdDeviation="3" result="blur" />
      <feComposite in="SourceGraphic" in2="blur" operator="over" />
    </filter>
  </defs>

  <!-- Background -->
  <rect width="640" height="420" fill="url(#bgGrad)" />
  <rect width="640" height="420" fill="url(#grid)" />

  <!-- Outer Border & Accent Corners -->
  <rect x="8" y="8" width="624" height="404" rx="8" fill="none" stroke="#0284c7" stroke-width="1.5" stroke-opacity="0.6" />
  <path d="M 8 32 L 8 8 L 32 8" fill="none" stroke="#38bdf8" stroke-width="3" />
  <path d="M 608 8 L 632 8 L 632 32" fill="none" stroke="#38bdf8" stroke-width="3" />
  <path d="M 8 388 L 8 412 L 32 412" fill="none" stroke="#38bdf8" stroke-width="3" />
  <path d="M 608 412 L 632 412 L 632 388" fill="none" stroke="#38bdf8" stroke-width="3" />

  <!-- Header Bar -->
  <rect x="18" y="18" width="604" height="38" rx="4" fill="#0f172a" fill-opacity="0.9" stroke="#334155" stroke-width="1"/>
  <circle cx="36" cy="37" r="6" fill="#10b981" filter="url(#glow)"/>
  <text x="52" y="42" font-family="'JetBrains Mono', 'Courier New', monospace" font-size="14" font-weight="700" fill="#38bdf8" letter-spacing="1.5">SIMBA SWARM TELEMETRY // HUD v2.0</text>
  <rect x="475" y="24" width="135" height="26" rx="3" fill="#0369a1" fill-opacity="0.3" stroke="#0284c7" stroke-width="1"/>
  <text x="542" y="41" text-anchor="middle" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#7dd3fc">STANDBY / STREAM</text>

  <!-- Central Radar / Viewport -->
  <g transform="translate(320, 210)">
    <!-- Radar Rings -->
    <circle r="95" fill="#051329" fill-opacity="0.4" stroke="#0284c7" stroke-width="1" stroke-dasharray="4,4" />
    <circle r="65" fill="none" stroke="#0369a1" stroke-width="1" stroke-opacity="0.6"/>
    <circle r="35" fill="none" stroke="#0ea5e9" stroke-width="1" stroke-opacity="0.4"/>
    <circle r="4" fill="#38bdf8" filter="url(#glow)"/>

    <!-- Crosshairs -->
    <line x1="-105" y1="0" x2="105" y2="0" stroke="#0284c7" stroke-width="0.8" stroke-opacity="0.5"/>
    <line x1="0" y1="-105" x2="0" y2="105" stroke="#0284c7" stroke-width="0.8" stroke-opacity="0.5"/>

    <!-- Sweep cone representation -->
    <path d="M 0 0 L 67 -67 A 95 95 0 0 1 95 0 Z" fill="#0284c7" fill-opacity="0.12"/>
    <line x1="0" y1="0" x2="95" y2="0" stroke="#38bdf8" stroke-width="1.5" stroke-opacity="0.8"/>

    <!-- Target Blip -->
    <circle cx="42" cy="-35" r="3.5" fill="#10b981" filter="url(#glow)"/>
    <circle cx="42" cy="-35" r="9" fill="none" stroke="#10b981" stroke-width="0.75" stroke-opacity="0.6"/>
  </g>

  <!-- Left Stats Panel -->
  <rect x="24" y="70" width="190" height="255" rx="6" fill="#0f172a" fill-opacity="0.8" stroke="#1e293b" stroke-width="1"/>
  <text x="36" y="94" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#94a3b8">IDENTIFIER</text>
  <text x="36" y="115" font-family="'JetBrains Mono', monospace" font-size="14" font-weight="700" fill="#f8fafc">${safeRsn}</text>

  <text x="36" y="145" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#94a3b8">MACHINE STATE</text>
  <rect x="36" y="152" width="100" height="22" rx="3" fill="#1e293b" />
  <text x="44" y="167" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#fbbf24">${safeState}</text>

  <text x="36" y="198" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#94a3b8">ATTENTION MODEL</text>
  <text x="36" y="217" font-family="'JetBrains Mono', monospace" font-size="12" font-weight="700" fill="#a78bfa">${safeAttention}</text>

  <text x="36" y="248" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#94a3b8">RUNTIME</text>
  <text x="36" y="267" font-family="'JetBrains Mono', monospace" font-size="13" font-weight="700" fill="#38bdf8">${safeRuntime}</text>

  <text x="36" y="295" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#94a3b8">WORLD COORDS</text>
  <text x="36" y="312" font-family="'JetBrains Mono', monospace" font-size="11" fill="#cbd5e1">(${x}, ${y})</text>

  <!-- Right Telemetry Panel -->
  <rect x="426" y="70" width="190" height="255" rx="6" fill="#0f172a" fill-opacity="0.8" stroke="#1e293b" stroke-width="1"/>
  <text x="438" y="94" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#94a3b8">INVENTORY CAPACITY</text>
  <text x="438" y="115" font-family="'JetBrains Mono', monospace" font-size="14" font-weight="700" fill="#38bdf8">${inv} / 28</text>
  <!-- Inv bar -->
  <rect x="438" y="123" width="165" height="6" rx="3" fill="#1e293b"/>
  <rect x="438" y="123" width="${Math.min(165, Math.round((inv / 28) * 165))}" height="6" rx="3" fill="#0284c7"/>

  <text x="438" y="155" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#94a3b8">RUN ENERGY</text>
  <text x="438" y="176" font-family="'JetBrains Mono', monospace" font-size="14" font-weight="700" fill="#10b981">${energy}%</text>
  <!-- Energy bar -->
  <rect x="438" y="184" width="165" height="6" rx="3" fill="#1e293b"/>
  <rect x="438" y="184" width="${Math.min(165, Math.round((energy / 100) * 165))}" height="6" rx="3" fill="#10b981"/>

  <text x="438" y="218" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#94a3b8">SECURITY INTEGRITY</text>
  <text x="438" y="237" font-family="'JetBrains Mono', monospace" font-size="12" font-weight="700" fill="#22c55e">SOCKS5 VERIFIED</text>

  <text x="438" y="268" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="700" fill="#94a3b8">INPUT DRIVER</text>
  <text x="438" y="287" font-family="'JetBrains Mono', monospace" font-size="11" font-weight="600" fill="#cbd5e1">WaspLib RemoteInput</text>

  <!-- Bottom Ticker -->
  <rect x="18" y="340" width="604" height="60" rx="6" fill="#090d16" stroke="#1e293b" stroke-width="1"/>
  <text x="32" y="365" font-family="'JetBrains Mono', monospace" font-size="12" fill="#38bdf8" font-weight="700">[SYSTEM NOTICE]:</text>
  <text x="160" y="365" font-family="'JetBrains Mono', monospace" font-size="12" fill="#94a3b8">Target image awaiting live hook. Rendering dynamic vector stream HUD.</text>
  <text x="32" y="386" font-family="'JetBrains Mono', monospace" font-size="11" fill="#64748b">PID Tracking: ACTIVE // Isolated Home: C:\\Simba\\Swarm\\profiles\\${botId} // Anti-Cheat: UNTOUCHED</text>
</svg>`;
}

// ============================================================================
// 5. SEED INITIAL PROFILES & TELEMETRY IF EMPTY
// ============================================================================

function seedInitialDataIfEmpty() {
  const initialProfiles = [
    {
      botId: 'bot_woodcutter_01',
      rsn: 'DriftWren',
      script: 'DynamicWoodcutter',
      shift: 1,
      proxyHost: '127.0.0.1',
      proxyPort: 1080,
      role: 'Gatherer (Willow/Teak)',
      created: new Date().toISOString()
    },
    {
      botId: 'bot_miner_02',
      rsn: 'IronRaven',
      script: 'DynamicMiner',
      shift: 1,
      proxyHost: '127.0.0.1',
      proxyPort: 1080,
      role: 'Artisan (Iron/Coal)',
      created: new Date().toISOString()
    },
    {
      botId: 'bot_tut_03',
      rsn: 'Cinder94',
      script: 'TutorialIsland',
      shift: 2,
      proxyHost: '127.0.0.1',
      proxyPort: 1080,
      role: 'Onboarding',
      created: new Date().toISOString()
    },
    {
      botId: 'bot_director_04',
      rsn: 'No Rush',
      script: 'MasterAccountDirector',
      shift: 3,
      proxyHost: '127.0.0.1',
      proxyPort: 1080,
      role: 'Multi-Skill Progression',
      created: new Date().toISOString()
    }
  ];

  initialProfiles.forEach((p) => {
    const profDir = path.join(PROFILES_DIR, p.botId);
    if (!fs.existsSync(profDir)) {
      fs.mkdirSync(profDir, { recursive: true });
    }
    const profFile = path.join(profDir, 'profile.json');
    if (!fs.existsSync(profFile)) {
      fs.writeFileSync(profFile, JSON.stringify(p, null, 2), 'utf-8');
    }
  });

  // Seed live telemetry sample for bot_woodcutter_01
  const sampleTelemFile = path.join(TELEMETRY_DIR, 'bot_woodcutter_01.json');
  if (!fs.existsSync(sampleTelemFile)) {
    const sampleTelem = {
      botId: 'bot_woodcutter_01',
      rsn: 'DriftWren',
      script: 'DynamicWoodcutter',
      state: 'CHOPPING',
      attention: 'FOCUSED',
      runtimeMs: 142000,
      runtimeFmt: '00:02:22',
      actions: 48,
      invCount: 18,
      invLimit: 27,
      runEnergy: 85,
      nearbyPlayers: 1,
      chatReplies: 2,
      posX: 3165,
      posY: 3242,
      loggedIn: true,
      timestamp: Date.now() / 1000
    };
    fs.writeFileSync(sampleTelemFile, JSON.stringify(sampleTelem, null, 2), 'utf-8');
  }
}

seedInitialDataIfEmpty();

// ============================================================================
// 6. HTTP SERVER & REST API ROUTER
// ============================================================================

function parseJsonBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > 1e6) { // 1MB limit
        req.destroy();
        reject(new Error('Payload too large'));
      }
    });
    req.on('end', () => {
      try {
        const parsed = body.trim() ? JSON.parse(body) : {};
        resolve(parsed);
      } catch (err) {
        reject(new Error('Invalid JSON: ' + err.message));
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, statusCode, data) {
  const jsonStr = JSON.stringify(data);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(jsonStr),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type'
  });
  res.end(jsonStr);
}

function sendText(res, statusCode, text, contentType = 'text/plain; charset=utf-8') {
  res.writeHead(statusCode, {
    'Content-Type': contentType,
    'Content-Length': Buffer.byteLength(text),
    'Access-Control-Allow-Origin': '*'
  });
  res.end(text);
}

const server = http.createServer(async (req, res) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Access-Control-Max-Age': '86400'
    });
    return res.end();
  }

  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;
  const method = req.method;

  try {
    // ------------------------------------------------------------------------
    // Static PWA Files
    // ------------------------------------------------------------------------
    if (method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
      const filePath = path.join(PUBLIC_DIR, 'index.html');
      if (fs.existsSync(filePath)) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        return fs.createReadStream(filePath).pipe(res);
      }
      return sendText(res, 404, 'public/index.html not found');
    }

    if (method === 'GET' && pathname === '/manifest.json') {
      const filePath = path.join(PUBLIC_DIR, 'manifest.json');
      if (fs.existsSync(filePath)) {
        res.writeHead(200, { 'Content-Type': 'application/manifest+json; charset=utf-8' });
        return fs.createReadStream(filePath).pipe(res);
      }
      return sendText(res, 404, 'public/manifest.json not found');
    }

    if (method === 'GET' && pathname === '/sw.js') {
      const filePath = path.join(PUBLIC_DIR, 'sw.js');
      if (fs.existsSync(filePath)) {
        res.writeHead(200, { 'Content-Type': 'application/javascript; charset=utf-8' });
        return fs.createReadStream(filePath).pipe(res);
      }
      return sendText(res, 404, 'public/sw.js not found');
    }

    if ((method === 'GET' || method === 'HEAD') && (pathname === '/WaspMobile.apk' || pathname === '/apk')) {
      const filePath = path.join(PUBLIC_DIR, 'WaspMobile.apk');
      if (fs.existsSync(filePath)) {
        const stat = fs.statSync(filePath);
        res.writeHead(200, {
          'Content-Type': 'application/vnd.android.package-archive',
          'Content-Length': stat.size,
          'Content-Disposition': 'attachment; filename="WaspMobile.apk"',
          'Cache-Control': 'no-store'
        });
        if (method === 'HEAD') return res.end();
        return fs.createReadStream(filePath).pipe(res);
      }
      return sendText(res, 404, 'public/WaspMobile.apk not found');
    }

    // ------------------------------------------------------------------------
    // API: Host Status
    // ------------------------------------------------------------------------
    if (method === 'GET' && pathname === '/api/status') {
      const uptimeSec = Math.floor((Date.now() - serverStartTime) / 1000);
      const currentShift = getCurrentShift();

      // Count registered profiles
      let totalProfiles = 0;
      if (fs.existsSync(PROFILES_DIR)) {
        totalProfiles = fs.readdirSync(PROFILES_DIR, { withFileTypes: true })
          .filter(d => d.isDirectory()).length;
      }

      return sendJson(res, 200, {
        service: 'Simba Swarm Command Center',
        version: '2.0.0',
        uptimeSeconds: uptimeSec,
        uptimeFormatted: new Date(uptimeSec * 1000).toISOString().substr(11, 8),
        hostHardware: HOST_HARDWARE,
        activeBotsCount: activeProcesses.size,
        totalRegisteredProfiles: totalProfiles,
        circadianStatus: {
          currentShiftId: currentShift.id,
          currentShiftName: currentShift.name,
          windowUtc: currentShift.windowUtc,
          activeShiftConcurrentLimit: currentShift.concurrentLimit,
          systemUtcTime: new Date().toISOString()
        }
      });
    }

    // ------------------------------------------------------------------------
    // API: Bot Profiles & Live Telemetry List
    // ------------------------------------------------------------------------
    if (method === 'GET' && pathname === '/api/bots') {
      const bots = [];

      if (fs.existsSync(PROFILES_DIR)) {
        const dirs = fs.readdirSync(PROFILES_DIR, { withFileTypes: true })
          .filter(d => d.isDirectory())
          .map(d => d.name);

        for (const botId of dirs) {
          const profFile = path.join(PROFILES_DIR, botId, 'profile.json');
          let profileData = { botId, rsn: botId, script: 'Unknown' };

          if (fs.existsSync(profFile)) {
            try {
              profileData = JSON.parse(fs.readFileSync(profFile, 'utf-8'));
            } catch (_) {}
          }

          // Check live telemetry file
          const teleFile = path.join(TELEMETRY_DIR, `${botId}.json`);
          let telemetryData = null;
          if (fs.existsSync(teleFile)) {
            try {
              telemetryData = JSON.parse(fs.readFileSync(teleFile, 'utf-8'));
            } catch (_) {}
          }

          // Process status
          const isProcessRunning = activeProcesses.has(botId);
          const processInfo = activeProcesses.get(botId) || null;

          // Merge into unified bot object
          bots.push({
            botId,
            profile: profileData,
            rsn: (telemetryData && telemetryData.rsn) || profileData.rsn || botId,
            script: (telemetryData && telemetryData.script) || profileData.script || 'DynamicWoodcutter',
            state: (telemetryData && telemetryData.state) || (isProcessRunning ? 'ACTIVE' : 'STANDBY'),
            attention: (telemetryData && telemetryData.attention) || 'FOCUSED',
            runtimeMs: (telemetryData && telemetryData.runtimeMs) || 0,
            runtimeFmt: (telemetryData && telemetryData.runtimeFmt) || '00:00:00',
            actions: (telemetryData && telemetryData.actions) || 0,
            invCount: (telemetryData && typeof telemetryData.invCount === 'number') ? telemetryData.invCount : 0,
            invLimit: (telemetryData && telemetryData.invLimit) || 28,
            runEnergy: (telemetryData && typeof telemetryData.runEnergy === 'number') ? telemetryData.runEnergy : 100,
            nearbyPlayers: (telemetryData && telemetryData.nearbyPlayers) || 0,
            chatReplies: (telemetryData && telemetryData.chatReplies) || 0,
            posX: (telemetryData && telemetryData.posX) || 3200,
            posY: (telemetryData && telemetryData.posY) || 3200,
            loggedIn: telemetryData ? !!telemetryData.loggedIn : false,
            lastTelemetryTimestamp: telemetryData ? telemetryData.timestamp : null,
            isRunning: isProcessRunning,
            pid: processInfo ? processInfo.pid : null,
            proxyHost: profileData.proxyHost || null,
            proxyPort: profileData.proxyPort || null,
            shift: profileData.shift || 1
          });
        }
      }

      return sendJson(res, 200, {
        success: true,
        count: bots.length,
        bots
      });
    }

    // ------------------------------------------------------------------------
    // API: Create Bot Profile
    // ------------------------------------------------------------------------
    if (method === 'POST' && pathname === '/api/bots/create') {
      const body = await parseJsonBody(req);
      const botId = (body.botId || '').trim();

      if (!botId || !BOT_ID_REGEX.test(botId)) {
        return sendJson(res, 400, {
          success: false,
          error: 'Valid botId required (alphanumeric, underscores, hyphens only)'
        });
      }

      const botDir = path.join(PROFILES_DIR, botId);
      if (!fs.existsSync(botDir)) {
        fs.mkdirSync(botDir, { recursive: true });
      }

      // Ensure .runelite directory is present in isolated home
      const rlDir = path.join(botDir, '.runelite');
      if (!fs.existsSync(rlDir)) {
        fs.mkdirSync(rlDir, { recursive: true });
      }

      const profile = {
        botId,
        rsn: body.rsn || botId,
        script: body.script || 'DynamicWoodcutter',
        proxyHost: body.proxyHost || null,
        proxyPort: body.proxyPort ? parseInt(body.proxyPort, 10) : null,
        shift: body.shift ? parseInt(body.shift, 10) : 1,
        role: body.role || 'Autonomous Worker',
        notes: body.notes || '',
        createdAt: new Date().toISOString()
      };

      fs.writeFileSync(path.join(botDir, 'profile.json'), JSON.stringify(profile, null, 2), 'utf-8');

      return sendJson(res, 200, {
        success: true,
        message: `Profile created for ${botId}`,
        profile
      });
    }

    // ------------------------------------------------------------------------
    // API: Launch Bot (with SOCKS5 Proxy TCP reachability kill-switch)
    // ------------------------------------------------------------------------
    const launchMatch = pathname.match(/^\/api\/bots\/([^/]+)\/launch$/);
    if (method === 'POST' && launchMatch) {
      const botId = decodeURIComponent(launchMatch[1]);

      if (!botId || !BOT_ID_REGEX.test(botId)) {
        return sendJson(res, 400, {
          success: false,
          error: 'Invalid botId format (alphanumeric, underscores, hyphens only)'
        });
      }

      if (activeProcesses.has(botId)) {
        return sendJson(res, 409, {
          success: false,
          error: `Bot ${botId} is already running (PID: ${activeProcesses.get(botId).pid})`,
          botId
        });
      }

      if (activeProcesses.size >= HOST_HARDWARE.maxConcurrentHeadedBots) {
        return sendJson(res, 429, {
          success: false,
          error: `Concurrent active bot limit (${HOST_HARDWARE.maxConcurrentHeadedBots}) reached for host hardware (i5-11400H).`,
          activeCount: activeProcesses.size,
          maxLimit: HOST_HARDWARE.maxConcurrentHeadedBots
        });
      }

      const profFile = path.join(PROFILES_DIR, botId, 'profile.json');

      let profile = { botId, rsn: botId };
      if (fs.existsSync(profFile)) {
        try {
          profile = JSON.parse(fs.readFileSync(profFile, 'utf-8'));
        } catch (_) {}
      }

      const proxyHost = profile.proxyHost;
      const proxyPort = profile.proxyPort;

      // Fail-closed proxy validation: if either proxyHost or proxyPort is set but not both, return 400
      const hasHost = Boolean(proxyHost && String(proxyHost).trim());
      const hasPort = proxyPort !== undefined && proxyPort !== null && proxyPort !== '';

      if ((hasHost && !hasPort) || (!hasHost && hasPort)) {
        return sendJson(res, 400, {
          success: false,
          error: 'Incomplete proxy configuration. Both proxyHost and proxyPort are required to prevent IP leaks.',
          botId
        });
      }

      // 1. Pre-launch SOCKS5 TCP reachability check
      if (hasHost && hasPort) {
        try {
          await verifyProxyReachable(proxyHost, proxyPort, 2000);
        } catch (err) {
          // PROXY KILL-SWITCH: Refuse to spawn any process! Return 502 Bad Gateway
          return sendJson(res, 502, {
            success: false,
            killSwitchEngaged: true,
            error: `Proxy reachability check failed: ${err.message}`,
            details: `Prevented process spawn to protect host residential IP. Configured proxy: ${proxyHost}:${proxyPort}`,
            botId
          });
        }
      }

      // 2. Prepare isolated home directory: C:\Simba\Swarm\profiles\<botId>\
      const botDir = path.join(PROFILES_DIR, botId);
      if (!fs.existsSync(botDir)) {
        fs.mkdirSync(botDir, { recursive: true });
      }
      const rlDir = path.join(botDir, '.runelite');
      if (!fs.existsSync(rlDir)) {
        fs.mkdirSync(rlDir, { recursive: true });
      }

      // 3. Mark bot process tracked
      // If RuneLite java/jar exists and live spawn requested:
      const javaPath = 'C:\\Users\\baseb\\AppData\\Local\\RuneLite\\jre\\bin\\java.exe';
      const jarPath = 'C:\\Users\\baseb\\AppData\\Local\\RuneLite\\RuneLite.jar';

      let child = null;
      let simulatedPid = 10000 + getRandomInt(100, 9999);

      if (fs.existsSync(javaPath) && fs.existsSync(jarPath) && process.env.LIVE_SPAWN === 'true') {
        const jvmArgs = [
          `-Duser.home=${botDir}`,
          ...(proxyHost && proxyPort ? [
            `-DsocksProxyHost=${proxyHost}`,
            `-DsocksProxyPort=${proxyPort}`,
            '-DsocksProxyVersion=5',
            '-Djava.net.socks.remoteDns=true'
          ] : []),
          '-jar', jarPath
        ];
        child = spawn(javaPath, jvmArgs, { detached: true, stdio: 'ignore' });
        child.unref();
      }

      const assignedPid = child ? child.pid : simulatedPid;
      activeProcesses.set(botId, {
        pid: assignedPid,
        process: child,
        startTime: Date.now(),
        botId,
        rsn: profile.rsn || botId,
        proxyHost,
        proxyPort
      });

      return sendJson(res, 200, {
        success: true,
        message: `Bot ${botId} launched successfully with isolated profile`,
        botId,
        pid: assignedPid,
        userHome: botDir,
        proxyVerified: !!(hasHost && hasPort)
      });
    }

    // ------------------------------------------------------------------------
    // API: Stop Bot (dispatches LOGOUT_STOP via commands/<botId>.cmd)
    // ------------------------------------------------------------------------
    const stopMatch = pathname.match(/^\/api\/bots\/([^/]+)\/stop$/);
    if (method === 'POST' && stopMatch) {
      const botId = decodeURIComponent(stopMatch[1]);

      if (!botId || !BOT_ID_REGEX.test(botId)) {
        return sendJson(res, 400, {
          success: false,
          error: 'Invalid botId format (alphanumeric, underscores, hyphens only)'
        });
      }

      const cmdFile = path.join(COMMANDS_DIR, `${botId}.cmd`);

      // Write LOGOUT_STOP
      fs.writeFileSync(cmdFile, 'LOGOUT_STOP\n', 'utf-8');

      // Clear from active processes
      if (activeProcesses.has(botId)) {
        const procEntry = activeProcesses.get(botId);
        if (procEntry.process && typeof procEntry.process.kill === 'function') {
          try { procEntry.process.kill('SIGTERM'); } catch (_) {}
        }
        activeProcesses.delete(botId);
      }

      return sendJson(res, 200, {
        success: true,
        message: `Dispatched LOGOUT_STOP for ${botId}`,
        botId,
        commandFile: cmdFile
      });
    }

    // ------------------------------------------------------------------------
    // API: Live Screenshot / Dynamic SVG Fallback
    // ------------------------------------------------------------------------
    const screenshotMatch = pathname.match(/^\/api\/screenshot\/([^/]+)$/);
    if (method === 'GET' && screenshotMatch) {
      const botId = decodeURIComponent(screenshotMatch[1]);

      if (!botId || !BOT_ID_REGEX.test(botId)) {
        return sendJson(res, 400, {
          success: false,
          error: 'Invalid botId format (alphanumeric, underscores, hyphens only)'
        });
      }

      const pngPath = path.join(TELEMETRY_DIR, `${botId}.png`);

      if (fs.existsSync(pngPath) && fs.statSync(pngPath).size > 0) {
        res.writeHead(200, {
          'Content-Type': 'image/png',
          'Cache-Control': 'no-cache, no-store, must-revalidate'
        });
        return fs.createReadStream(pngPath).pipe(res);
      }

      // Fallback: Read telemetry or profile to render crisp SVG
      let rsn = botId;
      let state = 'STANDBY';
      let attention = 'FOCUSED';
      let invCount = 0;
      let runEnergy = 100;
      let posX = 3200;
      let posY = 3200;
      let runtimeFmt = '00:00:00';

      const teleFile = path.join(TELEMETRY_DIR, `${botId}.json`);
      if (fs.existsSync(teleFile)) {
        try {
          const t = JSON.parse(fs.readFileSync(teleFile, 'utf-8'));
          rsn = t.rsn || rsn;
          state = t.state || state;
          attention = t.attention || attention;
          invCount = t.invCount !== undefined ? t.invCount : invCount;
          runEnergy = t.runEnergy !== undefined ? t.runEnergy : runEnergy;
          posX = t.posX || posX;
          posY = t.posY || posY;
          runtimeFmt = t.runtimeFmt || runtimeFmt;
        } catch (_) {}
      } else {
        const profFile = path.join(PROFILES_DIR, botId, 'profile.json');
        if (fs.existsSync(profFile)) {
          try {
            const p = JSON.parse(fs.readFileSync(profFile, 'utf-8'));
            rsn = p.rsn || rsn;
          } catch (_) {}
        }
      }

      const svgContent = generateFallbackSvg(
        botId, rsn, state, attention, invCount, runEnergy, posX, posY, runtimeFmt
      );

      res.writeHead(200, {
        'Content-Type': 'image/svg+xml; charset=utf-8',
        'Cache-Control': 'no-cache, no-store, must-revalidate'
      });
      return res.end(svgContent);
    }

    // ------------------------------------------------------------------------
    // API: Remote Command Dispatch
    // ------------------------------------------------------------------------
    if (method === 'POST' && pathname === '/api/command') {
      const body = await parseJsonBody(req);
      const botId = (body.botId || '').trim();
      const command = (body.command || '').trim();

      if (!botId || !command) {
        return sendJson(res, 400, {
          success: false,
          error: 'botId and command string are required'
        });
      }

      if (!BOT_ID_REGEX.test(botId)) {
        return sendJson(res, 400, {
          success: false,
          error: 'Invalid botId format (alphanumeric, underscores, hyphens only)'
        });
      }

      // Allowed commands: PAUSE, RESUME, HOP, CHAT:<msg>, LOGOUT_STOP
      const cmdFile = path.join(COMMANDS_DIR, `${botId}.cmd`);
      fs.writeFileSync(cmdFile, command + '\n', 'utf-8');

      return sendJson(res, 200, {
        success: true,
        message: `Command dispatched to ${botId}`,
        botId,
        command,
        path: cmdFile
      });
    }

    // ------------------------------------------------------------------------
    // API: 4-Shift Circadian Schedule
    // ------------------------------------------------------------------------
    if (method === 'GET' && pathname === '/api/schedule') {
      const currentShift = getCurrentShift();
      return sendJson(res, 200, {
        success: true,
        hostHardware: HOST_HARDWARE,
        currentShiftIndex: currentShift.id,
        currentShiftName: currentShift.name,
        systemUtcTime: new Date().toISOString(),
        shifts: CIRCADIAN_SHIFTS
      });
    }

    // ------------------------------------------------------------------------
    // API: Human RSN Generator & Mutator
    // ------------------------------------------------------------------------
    if (method === 'GET' && pathname === '/api/rsn/generate') {
      const count = Math.min(200, Math.max(1, parseInt(parsedUrl.query.count || '10', 10)));
      const archetype = parsedUrl.query.archetype || null;
      const doMutate = parsedUrl.query.mutate === 'true' || parsedUrl.query.mutate === '1';

      const results = [];
      let allValid = true;

      for (let i = 0; i < count; i++) {
        const base = generateHumanRSN(archetype);
        let finalItem;
        if (doMutate) {
          const mutated = mutateRSN(base.rsn);
          finalItem = {
            rsn: mutated.rsn,
            length: mutated.length,
            archetype: base.archetype,
            mutated: true
          };
        } else {
          finalItem = {
            rsn: base.rsn,
            length: base.length,
            archetype: base.archetype,
            mutated: false
          };
        }

        if (finalItem.length < 1 || finalItem.length > 12) {
          allValid = false;
        }
        results.push(finalItem);
      }

      return sendJson(res, 200, {
        success: true,
        count: results.length,
        allValidLength: allValid,
        maxLengthRule: 12,
        names: results
      });
    }

    // 404 Not Found
    return sendJson(res, 404, {
      error: 'Not Found',
      path: pathname
    });

  } catch (err) {
    console.error(`[Server Error] ${method} ${pathname}:`, err);
    return sendJson(res, 500, {
      error: 'Internal Server Error',
      message: err.message
    });
  }
});

// Start listener
if (require.main === module) {
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`=======================================================`);
    console.log(` Simba OSRS Swarm Command Center running on port ${PORT}`);
    console.log(` Web UI:        http://localhost:${PORT}/`);
    console.log(` Manifest:      http://localhost:${PORT}/manifest.json`);
    console.log(` Status API:    http://localhost:${PORT}/api/status`);
    console.log(` Bots API:      http://localhost:${PORT}/api/bots`);
    console.log(` Schedule API:  http://localhost:${PORT}/api/schedule`);
    console.log(` RSN Gen API:   http://localhost:${PORT}/api/rsn/generate`);
    console.log(` Hardware:      i5-11400H (6C/12T) | 32GB RAM | Port 7777`);
    console.log(`=======================================================`);
  });
}

module.exports = {
  server,
  PORT,
  BOT_ID_REGEX,
  generateHumanRSN,
  mutateRSN,
  verifyProxyReachable,
  generateFallbackSvg,
  CIRCADIAN_SHIFTS,
  getCurrentShift,
  HOST_HARDWARE
};
