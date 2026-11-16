import type { Metadata } from 'next';
import { AvisosProvider } from '@/components/ui';
import './globals.css';

export const metadata: Metadata = {
  title: 'Financeiro',
  description: 'Módulo Financeiro — contas, cobranças, recorrências, inadimplência e conciliação',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="pt-BR">
      <body>
        <AvisosProvider>{children}</AvisosProvider>
      </body>
    </html>
  );
}
