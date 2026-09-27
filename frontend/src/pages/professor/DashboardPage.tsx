import { Navigate } from 'react-router-dom';

/** Legacy route — replaced by /courses in Phase 2. */
const DashboardPage = () => <Navigate to="/courses" replace />;

export default DashboardPage;
