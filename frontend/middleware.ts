import { NextRequest, NextResponse } from 'next/server';

/** Sem sessão, qualquer tela do módulo leva para /entrar (que aguarda o token do sistema principal). */
export function middleware(req: NextRequest) {
  if (!req.cookies.get('financeiro_token')) {
    const url = new URL('/entrar', req.url);
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ['/((?!entrar|sessao|dev|api|_next|favicon).*)'],
};
