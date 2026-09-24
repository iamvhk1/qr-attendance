import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../../components/ui';
import { ArrowRight } from 'lucide-react';
import './LandingPage.css';

const LandingPage: React.FC = () => {
  const navigate = useNavigate();

  return (
    <main className="landing-root">
      {/* Animated glow orbs */}
      <div className="landing-orb landing-orb-1" aria-hidden="true" />
      <div className="landing-orb landing-orb-2" aria-hidden="true" />
      <div className="landing-orb landing-orb-3" aria-hidden="true" />

      {/* Centered content */}
      <div className="landing-content anim-fade-in-scale">
        <h1 className="landing-title">
          Attendance<span className="gradient-text">Helper</span>
        </h1>

        <Button
          variant="primary"
          size="lg"
          rightIcon={<ArrowRight size={18} />}
          onClick={() => navigate('/login')}
          id="landing-login-btn"
        >
          Sign In
        </Button>
      </div>
    </main>
  );
};

export default LandingPage;
