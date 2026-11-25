import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import { AdminLayout } from './components/layout/AdminLayout';
import { LoginPage } from './pages/LoginPage';
import { MainDashboardPage } from './pages/MainDashboardPage';
import { PointsDashboardPage } from './pages/variants/PointsDashboardPage';
import { DealsDashboardPage } from './pages/variants/DealsDashboardPage';
import { PoolDashboardPage } from './pages/variants/PoolDashboardPage';
import { TwentyOneDashboardPage } from './pages/variants/TwentyOneDashboardPage';
import { LiveTablesPage } from './pages/LiveTablesPage';
import { HandReplayPage } from './pages/HandReplayPage';
import { GameDetailPage } from './pages/GameDetailPage';
import { RulesConfigPage } from './pages/RulesConfigPage';
import { TournamentManagementPage } from './pages/TournamentManagementPage';
import { TournamentDetailPage } from './pages/TournamentDetailPage';
import { PrivateRoomsPage } from './pages/PrivateRoomsPage';
import { PrivateRoomDetailPage } from './pages/PrivateRoomDetailPage';

const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return <div style={{ color: '#fff', padding: '24px' }}>Verifying operator session...</div>;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
};

export const App: React.FC = () => {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />

          <Route
            path="/"
            element={
              <ProtectedRoute>
                <AdminLayout />
              </ProtectedRoute>
            }
          >
            <Route index element={<MainDashboardPage />} />
            <Route path="variants/points" element={<PointsDashboardPage />} />
            <Route path="variants/deals" element={<DealsDashboardPage />} />
            <Route path="variants/pool" element={<PoolDashboardPage />} />
            <Route path="variants/21-card" element={<TwentyOneDashboardPage />} />
            <Route path="tables" element={<LiveTablesPage />} />
            <Route path="games/:gameId" element={<GameDetailPage />} />
            <Route path="replay" element={<HandReplayPage />} />
            <Route path="config" element={<RulesConfigPage />} />
            <Route path="tournaments" element={<TournamentManagementPage />} />
            <Route path="tournaments/:tournamentId" element={<TournamentDetailPage />} />
            <Route path="private-rooms" element={<PrivateRoomsPage />} />
            <Route path="private-rooms/:roomCode" element={<PrivateRoomDetailPage />} />
          </Route>

          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
};
