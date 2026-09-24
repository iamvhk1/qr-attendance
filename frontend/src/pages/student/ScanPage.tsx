import React from 'react';
import { Card, CardHeader, Badge, Loader } from '../../components/ui';
import { QrCode } from 'lucide-react';

/**
 * ScanPage — Phase 1 placeholder (mobile-first).
 * Full implementation in Phase 3 (scan JWT, heartbeat, dwell timer).
 */
const ScanPage: React.FC = () => (
  <main
    style={{
      minHeight: '100dvh',
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      justifyContent: 'center',
      padding: 'var(--space-6)',
    }}
  >
    <Card glass accent padding="lg" style={{ maxWidth: 380, width: '100%' } as React.CSSProperties}>
      <CardHeader
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)' }}>
            <QrCode size={22} color="var(--accent-400)" />
            <span>QR Attendance</span>
          </div>
        }
        subtitle="Student scan interface"
        action={<Badge variant="success" dot>Phase 3</Badge>}
      />
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 'var(--space-4)', padding: 'var(--space-6) 0' }}>
        <Loader variant="pulse" size="lg" />
        <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem', textAlign: 'center', maxWidth: '100%' }}>
          Scan interface coming in Phase 3.<br />
          Presence verification, heartbeat & dwell timer.
        </p>
      </div>
    </Card>
  </main>
);

export default ScanPage;
