import React from 'react';
import { Card, CardHeader, Badge, Loader } from '../../components/ui';
import { LayoutDashboard } from 'lucide-react';

/**
 * DashboardPage — Phase 1 placeholder.
 * Full implementation in Phase 2 (course management, sessions, SSE).
 */
const DashboardPage: React.FC = () => (
  <main style={{ padding: 'var(--space-8)', maxWidth: 900, margin: '0 auto' }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-4)', marginBottom: 'var(--space-8)' }}>
      <LayoutDashboard size={32} color="var(--primary-400)" />
      <div>
        <h1 style={{ margin: 0 }}>Professor Dashboard</h1>
        <p style={{ margin: 0, color: 'var(--text-muted)', fontSize: '0.875rem' }}>Coming in Phase 2</p>
      </div>
    </div>

    <Card glass accent hoverable padding="lg">
      <CardHeader
        title="Under Construction"
        subtitle="Course management, session control &amp; live SSE stream"
        action={<Badge variant="warning" dot>Phase 2</Badge>}
      />
      <div style={{ display: 'flex', justifyContent: 'center', padding: 'var(--space-8) 0' }}>
        <Loader variant="dots" size="lg" label="Phase 2 coming soon" />
      </div>
    </Card>
  </main>
);

export default DashboardPage;
