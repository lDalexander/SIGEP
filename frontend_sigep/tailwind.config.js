/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    "./src/**/*.{js,jsx,ts,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        'sigep-deep':    '#0a0f1a',
        'sigep-card':    '#0f172a',
        'sigep-border':  '#1e293b',
        'sigep-border2': '#334155',
        'sigep-neon':    '#00E887',
        'sigep-neon-dim':'#00c472',
        'sigep-terminal':'#030712',
        'sigep-danger':  '#f87171',
        'sigep-warning': '#fbbf24',
        'sigep-info':    '#38bdf8',
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'ui-monospace', 'monospace'],
      },
      animation: {
        'blink': 'blink 1.1s step-end infinite',
        'glow-pulse': 'glow-pulse 2.5s ease-in-out infinite',
        'fade-in': 'fade-in 0.5s cubic-bezier(0.16,1,0.3,1) both',
        'slide-in': 'slide-in 0.35s cubic-bezier(0.16,1,0.3,1) both',
        'shimmer': 'shimmer 1.5s ease-in-out infinite',
      },
      keyframes: {
        blink: {
          '0%, 100%': { opacity: '1' },
          '50%':      { opacity: '0' },
        },
        'glow-pulse': {
          '0%, 100%': { boxShadow: '0 0 4px rgba(0,232,135,0.25)' },
          '50%':      { boxShadow: '0 0 14px rgba(0,232,135,0.5)' },
        },
        'fade-in': {
          from: { opacity: '0', transform: 'translateY(12px)' },
          to:   { opacity: '1', transform: 'translateY(0)' },
        },
        'slide-in': {
          from: { opacity: '0', transform: 'translateX(-10px)' },
          to:   { opacity: '1', transform: 'translateX(0)' },
        },
        'shimmer': {
          '0%':   { backgroundPosition: '200% 0' },
          '100%': { backgroundPosition: '-200% 0' },
        },
      },
    },
  },
  plugins: [],
};
