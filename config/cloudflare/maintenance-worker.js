// Cloudflare Worker - intercepts tunnel/origin errors and serves a branded
// maintenance page instead of Cloudflare's default error pages (1033, 502, etc).
// Deployed via cloudflare-setup.sh on routes: *.DOMAIN/*

const PAGES = {
  map: {
    title: 'Map',
    active: 'Map',
    message: 'The map renderer is currently offline. It will be back when the server restarts.',
  },
  pack: {
    title: 'Downloads',
    active: 'Download',
    message: 'The download server is currently offline. Try again in a few minutes.',
  },
  status: {
    title: 'Status',
    active: 'Status',
    message: 'The status dashboard is currently offline. Try again in a few minutes.',
  },
  mods: {
    title: 'Mods',
    active: 'Mods',
    message: 'The mod status page is currently offline. Try again in a few minutes.',
  },
  default: { title: 'Server', active: '', message: 'This service is currently offline. Try again in a few minutes.' },
}

function getPageConfig(hostname) {
  const sub = hostname.split('.')[0]
  return PAGES[sub] || PAGES.default
}

function maintenancePage(hostname) {
  const page = getPageConfig(hostname)
  const domain = hostname.replace(/^[^.]+\./, '')

  const html = `<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${page.title} Offline</title><meta property="og:site_name" content="Server"><meta property="og:title" content="${page.title} Offline"><meta property="og:description" content="${page.message}"><meta property="og:image" content="https://pack.${domain}/og-image.jpg"><meta name="twitter:card" content="summary"><link rel="icon" href="https://pack.${domain}/favicon.ico"><link rel="apple-touch-icon" href="https://pack.${domain}/apple-touch-icon.png"><style>*{margin:0;padding:0;box-sizing:border-box}body{font-family:system-ui,-apple-system,sans-serif;background:#0c1319;color:#c5cdd8;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center}.site-nav{position:fixed;top:0;left:0;right:0;z-index:99999;display:flex;flex-wrap:wrap;align-items:center;gap:1.5rem;padding:.75rem 1.5rem;border-bottom:1px solid #1c2835;background:#080d12;font-family:system-ui,-apple-system,sans-serif;font-size:.875rem}.site-nav-brand{display:inline-flex;align-items:center;gap:.5rem;font-weight:700;font-size:1.1rem;color:#e8ecf1;letter-spacing:.03em;margin-right:auto;text-decoration:none}.site-nav-brand img{width:28px;height:28px;border-radius:6px}.site-nav a{color:#7a8999;text-decoration:none;transition:color .15s}.site-nav a:hover{color:#c5cdd8}.site-nav a[aria-current]{color:#5a9a70}.box{text-align:center;padding:2rem}h1{font-family:system-ui,-apple-system,sans-serif;font-size:1.5rem;margin-bottom:.5rem;color:#e8ecf1;letter-spacing:.02em}p{color:#7a8999;max-width:28rem}</style></head><body><nav class="site-nav"><a href="https://pack.${domain}" class="site-nav-brand"><img src="https://pack.${domain}/icon.svg" alt="" role="presentation" width="28" height="28" loading="eager" decoding="async">Server</a><a href="https://map.${domain}"${page.active === 'Map' ? ' aria-current="page"' : ''}>Map</a><a href="https://status.${domain}"${page.active === 'Status' ? ' aria-current="page"' : ''}>Status</a><a href="https://pack.${domain}"${page.active === 'Download' ? ' aria-current="page"' : ''}>Download</a><a href="https://mods.${domain}"${page.active === 'Mods' ? ' aria-current="page"' : ''}>Mods</a></nav><div class="box"><h1>${page.title} is offline</h1><p>${page.message}</p></div></body></html>`

  return new Response(html, {
    status: 503,
    headers: { 'Content-Type': 'text/html;charset=utf-8', 'Cache-Control': 'no-store', 'Retry-After': '60' },
  })
}

export default {
  async fetch(request) {
    try {
      const response = await fetch(request)
      if (response.status >= 500) {
        const ct = response.headers.get('content-type') || ''
        if (ct.includes('text/html')) {
          const body = await response.text()
          if (body.includes('cloudflare') || body.includes('Cloudflare')) {
            return maintenancePage(new URL(request.url).hostname)
          }
        }
        if (
          response.status === 530 ||
          response.status === 521 ||
          response.status === 522 ||
          response.status === 523 ||
          response.status === 524
        ) {
          return maintenancePage(new URL(request.url).hostname)
        }
      }
      return response
    } catch {
      return maintenancePage(new URL(request.url).hostname)
    }
  },
}
