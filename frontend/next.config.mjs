/**
 * O navegador fala só com este servidor (mesma origem). As chamadas a /api/financeiro/* são
 * repassadas à API Java (Spring Boot), levando junto o cookie httpOnly com o JWT — o token nunca fica
 * acessível ao JavaScript da página (nem em localStorage), reduzindo a exposição a XSS.
 */
const API = process.env.FINANCEIRO_API_URL ?? 'http://localhost:3008';
// Quem pode incorporar este frontend em iframe (o sistema principal). Ex.: "'self' https://erp.exemplo.com.br"
const FRAME_ANCESTORS = process.env.FRAME_ANCESTORS ?? "'self'";

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  async rewrites() {
    return [{ source: '/api/financeiro/:path*', destination: `${API}/api/financeiro/:path*` }];
  },
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'Content-Security-Policy', value: `frame-ancestors ${FRAME_ANCESTORS}` },
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'Referrer-Policy', value: 'same-origin' },
        ],
      },
    ];
  },
};

export default nextConfig;
