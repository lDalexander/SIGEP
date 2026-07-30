/** @type {import('tailwindcss').Config} */

/* Tokens del sistema de diseño SIGEP — tema oscuro industrial, «centro de control».
   Los valores salen de las capturas de referencia_ui/ y están documentados en
   CLAUDE.md §5. Cualquier color nuevo se añade aquí, nunca suelto en un componente. */
module.exports = {
  content: [
    "./src/**/*.{js,jsx,ts,tsx}",
  ],
  theme: {
    extend: {
      screens: {
        // El layout de dos columnas colapsa a una sola por debajo de 1100px.
        wide: '1100px',
      },
      colors: {
        sig: {
          bg:     '#0A100E',  // fondo, casi negro con tinte verde
          card:   '#101815',  // tarjetas
          input:  '#16201C',  // inputs y chips
          line:   'rgba(255,255,255,0.07)', // bordes 1px
          text:   '#E7EFEB',  // texto principal
          muted:  '#7C8C86',  // etiquetas y metadatos
          dim:    '#5A6A64',  // texto tenue, índices, estados vacíos
          amber:  '#F5A623',  // acento: primarios, barras, checks, pestaña activa
          ok:     '#22C55E',  // verde: activo, ENTRADA, «X» del checklist
        },
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      letterSpacing: {
        // Tracking de las etiquetas monoespaciadas en mayúsculas.
        label: '0.08em',
      },
      animation: {
        'pulse-dot': 'pulse-dot 2s ease-in-out infinite',
        'fade-in': 'fade-in 0.4s cubic-bezier(0.16,1,0.3,1) both',
        'shimmer': 'shimmer 1.5s ease-in-out infinite',
      },
      keyframes: {
        // Punto verde del indicador «EN VIVO».
        'pulse-dot': {
          '0%, 100%': { opacity: '1', boxShadow: '0 0 0 0 rgba(34,197,94,0.45)' },
          '50%':      { opacity: '0.65', boxShadow: '0 0 0 4px rgba(34,197,94,0)' },
        },
        'fade-in': {
          from: { opacity: '0', transform: 'translateY(8px)' },
          to:   { opacity: '1', transform: 'translateY(0)' },
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
