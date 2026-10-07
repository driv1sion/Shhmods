import { useState, useEffect } from 'react';
import { Users, FileText, Settings, Activity, Server, AlertTriangle, CheckCircle, Clock } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

function App() {
  const [isDarkMode, setIsDarkMode] = useState(false);
  const [activeTab, setActiveTab] = useState('dashboard');
  const [flags, setFlags] = useState<any[]>([]);
  const [leaderboard, setLeaderboard] = useState<any[]>([]);
  const [audit, setAudit] = useState<any[]>([]);

  
  useEffect(() => {
    if (isDarkMode) {
      document.documentElement.classList.add('dark');
    } else {
      document.documentElement.classList.remove('dark');
    }
  }, [isDarkMode]);

  useEffect(() => {
    fetch('http://localhost:8080/api/v1/admin/flags')
      .then(r => r.json())
      .then(data => setFlags(data.length ? data : mockFlags))
      .catch(() => setFlags(mockFlags));

    fetch('http://localhost:8080/api/v1/admin/leaderboard')
      .then(r => r.json())
      .then(data => setLeaderboard(data.length ? data : mockLeaderboard))
      .catch(() => setLeaderboard(mockLeaderboard));

    fetch('http://localhost:8080/api/v1/admin/audit')
      .then(r => r.json())
      .then(data => setAudit(data.length ? data : mockAudit))
      .catch(() => setAudit(mockAudit));
  }, []);

  const handleAction = async (endpoint: string, payload: any) => {
    try {
      await fetch(`http://localhost:8080/api/v1/admin/${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      // Refresh flags
      fetch('http://localhost:8080/api/v1/admin/flags')
        .then(r => r.json())
        .then(data => setFlags(data.length ? data : mockFlags));
    } catch (e) {
      console.error("Action failed", e);
    }
  };

  const handleNewPolicy = async () => {
    const policy = {
      tenant_id: "GLOBAL",
      rule_name: "NEW_CUSTOM_RULE",
      override_action: "BYPASS",
      condition_json: {}
    };
    await handleAction('policy', policy);
  };

  const navItemClass = (tab: string) =>
    `flex items-center gap-3 cursor-pointer px-3 py-2 text-sm font-medium rounded-none transition-colors ${activeTab === tab
      ? 'bg-orange-50 dark:bg-orange-500/10 text-orange-600 dark:text-orange-500'
      : 'text-gray-500 dark:text-zinc-400 hover:text-gray-900 dark:text-zinc-100 hover:bg-gray-50 dark:hover:bg-zinc-800/50 dark:bg-zinc-900'
    }`;

  return (
    <div className="min-h-screen flex bg-[#FAFAFA] dark:bg-black font-sans selection:bg-blue-100 selection:text-blue-900">

      {/* Sidebar */}
      <div className="w-64 bg-white dark:bg-zinc-900 border-r border-gray-200 dark:border-zinc-800 px-4 py-6 flex flex-col gap-8 h-screen sticky top-0">
        <div className="flex items-center gap-2 px-3">
          <h1 className="text-lg font-semibold text-gray-900 dark:text-zinc-100 tracking-tight">Shhmods</h1>
        </div>

        <nav className="flex flex-col gap-1 flex-1">
          <div className={navItemClass('dashboard')} onClick={() => setActiveTab('dashboard')}>
            <Activity className="w-4 h-4" /> Home
          </div>
          <div className={navItemClass('flags')} onClick={() => setActiveTab('flags')}>
            <AlertTriangle className="w-4 h-4" /> Flag Queue
            {flags.length > 0 && <span className="ml-auto bg-gray-100 dark:bg-zinc-800 text-gray-600 dark:text-zinc-400 text-xs py-0.5 px-2 rounded-none-full font-medium border border-gray-200 dark:border-zinc-800">{flags.length}</span>}
          </div>
          <div className={navItemClass('leaderboard')} onClick={() => setActiveTab('leaderboard')}>
            <Users className="w-4 h-4" /> Analytics
          </div>
          <div className={navItemClass('audit')} onClick={() => setActiveTab('audit')}>
            <FileText className="w-4 h-4" /> Ledger
          </div>
          <div className={navItemClass('config')} onClick={() => setActiveTab('config')}>
            <Settings className="w-4 h-4" /> Policies
          </div>
        </nav>

        <div className="mt-auto pt-4 border-t border-gray-100 dark:border-zinc-800/50">
          <div className="flex items-center gap-2 text-xs font-medium text-gray-500 dark:text-zinc-400 px-3">
            <Server className="w-3.5 h-3.5" />
            Engine Online
          </div>
        <div className="mt-4 flex justify-center">
          <button onClick={() => setIsDarkMode(!isDarkMode)} className="w-full py-1.5 px-3 rounded-none text-xs font-medium border border-gray-200 dark:border-zinc-800 text-gray-700 dark:text-zinc-300 hover:bg-gray-50 dark:hover:bg-zinc-800/50 transition-colors">
            {isDarkMode ? "Light mode" : "Dark mode"}
          </button>
        </div>

        </div>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 p-10 overflow-auto">
        <div className="max-w-5xl mx-auto">

          <AnimatePresence mode="wait">
            {activeTab === 'dashboard' && (
              <motion.div key="dashboard" initial={{ opacity: 0, y: 5 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -5 }} transition={{ duration: 0.15 }} className="space-y-8">
                <div>
                  <h2 className="text-2xl font-semibold text-gray-900 dark:text-zinc-100 tracking-tight">Home</h2>
                  <p className="text-gray-500 dark:text-zinc-400 text-sm mt-1">Real-time moderation metrics and active clusters.</p>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                  <StatCard title="Active Clusters" value="12" icon={<Activity className="text-gray-400 dark:text-zinc-500 w-4 h-4" />} trend="+3 this hour" />
                  <StatCard title="Auto-Resolved" value="98.2%" icon={<CheckCircle className="text-gray-400 dark:text-zinc-500 w-4 h-4" />} trend="Deterministic Engine" />
                  <StatCard title="Avg Latency" value="14ms" icon={<Clock className="text-gray-400 dark:text-zinc-500 w-4 h-4" />} trend="PostgreSQL Native" />
                </div>

                <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                  <div className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 p-5 rounded-none-none shadow-sm">
                    <h3 className="text-sm font-semibold text-gray-900 dark:text-zinc-100 mb-4">Graph Intelligence</h3>
                    <div className="h-32 border border-gray-100 dark:border-zinc-800/50 rounded-none bg-gray-50 dark:bg-zinc-900 flex items-center justify-center">
                      <p className="text-gray-500 dark:text-zinc-400 font-mono text-xs">4 active content_clusters detected</p>
                    </div>
                  </div>
                  <div className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 p-5 rounded-none-none shadow-sm">
                    <h3 className="text-sm font-semibold text-gray-900 dark:text-zinc-100 mb-4">Recent Ledger Commits</h3>
                    <div className="space-y-2">
                      {[1, 2, 3].map(i => (
                        <div key={i} className="flex justify-between items-center text-sm">
                          <span className="text-gray-900 dark:text-zinc-100 font-medium">BLOCK</span>
                          <span className="text-gray-500 dark:text-zinc-400 font-mono text-xs">{"{spam_trust: 12.0}"}</span>
                          <span className="text-gray-400 dark:text-zinc-500">tenant_a</span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              </motion.div>
            )}

            {activeTab === 'flags' && (
              <motion.div key="flags" initial={{ opacity: 0, y: 5 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -5 }} transition={{ duration: 0.15 }}>
                <h2 className="text-2xl font-semibold text-gray-900 dark:text-zinc-100 tracking-tight mb-6">Review Queue</h2>
                <div className="space-y-3">
                  {flags.map((flag: any, i: number) => (
                    <div key={i} className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 p-5 rounded-none shadow-sm flex flex-col md:flex-row justify-between items-start md:items-center gap-4">
                      <div>
                        <div className="flex items-center gap-2 mb-1.5">
                          <span className="bg-gray-100 dark:bg-zinc-800 text-gray-700 dark:text-zinc-300 px-2 py-0.5 rounded-none text-xs font-medium">{flag.reason}</span>
                          <span className="text-gray-400 dark:text-zinc-500 text-xs">{flag.flagged_at || 'Just now'}</span>
                        </div>
                        <p className="text-gray-900 dark:text-zinc-100 text-sm mb-1">"{flag.content_text}"</p>
                        <p className="text-xs font-mono text-gray-400 dark:text-zinc-500">ID: {flag.content_id} • Hash: {flag.hash || 'a8f42e'}</p>
                      </div>
                      <div className="flex gap-2">
                        <button onClick={() => handleAction('dismiss', { content_id: flag.content_id })} className="px-4 py-1.5 rounded-none text-sm font-medium border border-gray-200 dark:border-zinc-800 text-gray-700 dark:text-zinc-300 hover:bg-gray-50 dark:hover:bg-zinc-800/50 dark:bg-zinc-900 transition-colors">Dismiss</button>
                        <button onClick={() => handleAction('ban', { content_id: flag.content_id })} className="px-4 py-1.5 rounded-none text-sm font-medium bg-orange-500 hover:bg-orange-600 dark:bg-orange-500 dark:hover:bg-orange-600 border-none text-white hover:bg-orange-600 transition-colors">Apply Ban</button>
                      </div>
                    </div>
                  ))}
                </div>
              </motion.div>
            )}

            {activeTab === 'leaderboard' && (
              <motion.div key="leaderboard" initial={{ opacity: 0, y: 5 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -5 }} transition={{ duration: 0.15 }}>
                <h2 className="text-2xl font-semibold text-gray-900 dark:text-zinc-100 tracking-tight mb-1">Trust Analytics</h2>
                <p className="text-gray-500 dark:text-zinc-400 text-sm mb-6">Real-time temporal decay metrics (effective_trust view).</p>

                <div className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 rounded-none-none shadow-sm overflow-hidden">
                  <table className="w-full text-left text-sm">
                    <thead className="bg-gray-50 dark:bg-zinc-900 border-b border-gray-200 dark:border-zinc-800">
                      <tr>
                        <th className="py-3 px-5 font-medium text-gray-500 dark:text-zinc-400">User</th>
                        <th className="py-3 px-5 font-medium text-gray-500 dark:text-zinc-400">Effective Trust</th>
                        <th className="py-3 px-5 font-medium text-gray-500 dark:text-zinc-400">Trend</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100 dark:divide-zinc-800">
                      {leaderboard.map((user: any, i: number) => (
                        <tr key={i}>
                          <td className="py-3 px-5 text-gray-900 dark:text-zinc-100">{user.username}</td>
                          <td className="py-3 px-5">
                            <div className="flex items-center gap-3">
                              <div className="h-1.5 w-24 bg-gray-100 dark:bg-zinc-800 rounded-none-full overflow-hidden">
                                <div className="h-full bg-orange-500 hover:bg-orange-600 dark:bg-orange-500 dark:hover:bg-orange-600 border-none" style={{ width: `${user.trust_score}%` }}></div>
                              </div>
                              <span className="text-gray-900 dark:text-zinc-100 font-mono text-xs">{user.trust_score.toFixed(1)}</span>
                            </div>
                          </td>
                          <td className="py-3 px-5 text-gray-500 dark:text-zinc-400 text-xs">Decaying Penalties</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </motion.div>
            )}

            {activeTab === 'config' && (
              <motion.div key="config" initial={{ opacity: 0, y: 5 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -5 }} transition={{ duration: 0.15 }}>
                <div className="flex justify-between items-end mb-6">
                  <div>
                    <h2 className="text-2xl font-semibold text-gray-900 dark:text-zinc-100 tracking-tight mb-1">Policy Overrides</h2>
                    <p className="text-gray-500 dark:text-zinc-400 text-sm">Inject JSON behavioral bypasses securely.</p>
                  </div>
                  <button onClick={handleNewPolicy} className="px-4 py-1.5 rounded-none text-sm font-medium bg-orange-500 hover:bg-orange-600 dark:bg-orange-500 dark:hover:bg-orange-600 border-none text-white hover:bg-orange-600 transition-colors">
                    New Policy
                  </button>
                </div>

                <div className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 p-5 rounded-none-none shadow-sm relative">
                  <div className="absolute top-5 right-5">
                    <span className="bg-gray-100 dark:bg-zinc-800 text-gray-600 dark:text-zinc-400 px-2.5 py-0.5 rounded-none-full text-xs font-medium border border-gray-200 dark:border-zinc-800">Active</span>
                  </div>
                  <h3 className="text-sm font-semibold text-gray-900 dark:text-zinc-100 mb-1">URL_FILTER_BYPASS</h3>
                  <p className="text-gray-500 dark:text-zinc-400 text-xs mb-4">Tenant: GLOBAL</p>

                  <div className="bg-gray-50 dark:bg-zinc-900 p-4 rounded-none border border-gray-200 dark:border-zinc-800 font-mono text-xs text-gray-600 dark:text-zinc-400 overflow-x-auto">
                    <pre>
                      {`{
  "rule_name": "URL_FILTER",
  "override_action": "BYPASS",
  "condition_json": {
    "min_spam_trust": 50.0,
    "min_account_age_days": 30.0
  }
}`}
                    </pre>
                  </div>
                </div>
              </motion.div>
            )}

            {activeTab === 'audit' && (
              <motion.div key="audit" initial={{ opacity: 0, y: 5 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -5 }} transition={{ duration: 0.15 }}>
                <h2 className="text-2xl font-semibold text-gray-900 dark:text-zinc-100 tracking-tight mb-1">Immutable Ledger</h2>
                <p className="text-gray-500 dark:text-zinc-400 text-sm mb-6">Raw moderation_event records. Secured by DB triggers.</p>
                <div className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 rounded-none-none shadow-sm divide-y divide-gray-100 dark:divide-zinc-800">
                  {audit.map((log: any, i: number) => (
                    <div key={i} className="p-4 flex flex-col md:flex-row gap-4 justify-between items-start md:items-center text-sm">
                      <div className="flex items-center gap-3">
                        <span className="bg-gray-100 dark:bg-zinc-800 text-gray-600 dark:text-zinc-400 text-xs font-mono px-2 py-0.5 rounded-none">{log.actor_type || 'ENGINE'}</span>
                        <span className="text-gray-900 dark:text-zinc-100 font-medium">{log.action_type || 'BLOCK'}</span>
                        <span className="text-gray-500 dark:text-zinc-400 font-mono text-xs">{log.reference_type || 'spam_trust < 10'}</span>
                      </div>
                      <span className="text-xs text-gray-400 dark:text-zinc-500 font-mono">{log.action_timestamp || new Date().toISOString()}</span>
                    </div>
                  ))}
                </div>
              </motion.div>
            )}

          </AnimatePresence>
        </div>
      </div>
    </div>
  );
}

function StatCard({ title, value, icon, trend }: { title: string, value: string, icon: React.ReactNode, trend: string }) {
  return (
    <div className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 p-5 rounded-none-none shadow-sm">
      <div className="flex justify-between items-start mb-2">
        <h3 className="text-gray-500 dark:text-zinc-400 text-sm font-medium">{title}</h3>
        {icon}
      </div>
      <p className="text-2xl font-semibold text-gray-900 dark:text-zinc-100 mb-1">{value}</p>
      <p className="text-xs text-gray-400 dark:text-zinc-500">{trend}</p>
    </div>
  )
}

const mockFlags = [
  { flag_id: 1, content_text: "Click here to win a free iPhone: http://spam.url", reason: "Graph Coordination", flagged_at: "Just now", report_count: 5, hash: "f3c92b1a" },
  { flag_id: 2, content_text: "You all suck! b@dword", reason: "Toxicity / Banned Word", flagged_at: "2 mins ago", report_count: 1, hash: "a9d82f3c" }
];
const mockLeaderboard = [
  { user_id: 1, username: "trusted_veteran", trust_score: 100.0 },
  { user_id: 2, username: "helpful_mod", trust_score: 98.5 },
  { user_id: 3, username: "active_user_01", trust_score: 92.0 }
];
const mockAudit = [
  { audit_id: 1, actor_type: 'DECISION_ENGINE', action_type: 'BLOCK', reference_type: '{"spam_trust": 15.0}', action_timestamp: new Date().toISOString() },
  { audit_id: 2, actor_type: 'DECISION_ENGINE', action_type: 'THROTTLE', reference_type: '{"burst_1m": 4}', action_timestamp: new Date(Date.now() - 5000).toISOString() },
  { audit_id: 3, actor_type: 'TENANT_POLICY', action_type: 'BYPASS_ALLOW', reference_type: '{"url_filter": bypassed}', action_timestamp: new Date(Date.now() - 15000).toISOString() }
];

export default App;
