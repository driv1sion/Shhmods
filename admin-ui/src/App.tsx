import { useState, useEffect } from 'react';

function App() {
  const [activeTab, setActiveTab] = useState('flags');
  const [flags, setFlags] = useState([]);
  const [leaderboard, setLeaderboard] = useState([]);
  const [audit, setAudit] = useState([]);
  const [config, setConfig] = useState({});

  useEffect(() => {
    fetch('http://localhost:8080/api/v1/admin/flags').then(r => r.json()).then(setFlags).catch(console.error);
    fetch('http://localhost:8080/api/v1/admin/leaderboard').then(r => r.json()).then(setLeaderboard).catch(console.error);
    fetch('http://localhost:8080/api/v1/admin/audit').then(r => r.json()).then(setAudit).catch(console.error);
    fetch('http://localhost:8080/api/v1/admin/config').then(r => r.json()).then(setConfig).catch(console.error);
  }, []);

  const navItemClass = (tab: string) => 
    `cursor-pointer px-4 py-2 font-medium rounded-md ${activeTab === tab ? 'bg-brand-500 text-white' : 'text-gray-600 hover:bg-gray-100'}`;

  return (
    <div className="min-h-screen flex flex-col md:flex-row bg-gray-50 font-sans">
      {/* Sidebar */}
      <div className="w-full md:w-64 bg-white border-r border-gray-200 p-6 flex flex-col gap-4 shadow-sm">
        <h1 className="text-2xl font-bold text-gray-900 mb-4 tracking-tight">Shhmods Admin</h1>
        <nav className="flex flex-col gap-2">
          <div className={navItemClass('flags')} onClick={() => setActiveTab('flags')}>Flag Queue</div>
          <div className={navItemClass('leaderboard')} onClick={() => setActiveTab('leaderboard')}>Trust Leaderboard</div>
          <div className={navItemClass('audit')} onClick={() => setActiveTab('audit')}>Audit Log</div>
          <div className={navItemClass('config')} onClick={() => setActiveTab('config')}>Tenant Config</div>
        </nav>
      </div>

      {/* Main Content */}
      <div className="flex-1 p-8 overflow-auto">
        <div className="max-w-5xl mx-auto bg-white rounded-xl shadow-sm border border-gray-100 p-8">
          
          {activeTab === 'flags' && (
            <div>
              <h2 className="text-xl font-semibold mb-6">Pending Flag Queue</h2>
              <div className="grid gap-4">
                {flags.map((flag: any) => (
                  <div key={flag.flag_id} className="border border-red-100 bg-red-50 p-4 rounded-lg flex justify-between items-start">
                    <div>
                      <p className="font-medium text-gray-900 mb-1">"{flag.content_text}"</p>
                      <p className="text-sm text-red-600 font-semibold">{flag.reason}</p>
                      <p className="text-xs text-gray-500 mt-2">{new Date(flag.flagged_at).toLocaleString()}</p>
                    </div>
                    <div className="flex gap-2">
                      <button className="bg-white border border-gray-300 px-3 py-1 rounded text-sm hover:bg-gray-50 transition-colors">Dismiss</button>
                      <button className="bg-red-600 text-white px-3 py-1 rounded text-sm shadow hover:bg-red-700 transition-colors">Confirm Ban</button>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {activeTab === 'leaderboard' && (
            <div>
              <h2 className="text-xl font-semibold mb-6">Top Trusted Users</h2>
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="border-b border-gray-200">
                    <th className="py-3 px-4 font-semibold text-gray-700">Username</th>
                    <th className="py-3 px-4 font-semibold text-gray-700">Trust Score</th>
                  </tr>
                </thead>
                <tbody>
                  {leaderboard.map((user: any) => (
                    <tr key={user.user_id} className="border-b border-gray-100 last:border-0 hover:bg-gray-50 transition-colors">
                      <td className="py-3 px-4 text-gray-900">{user.username}</td>
                      <td className="py-3 px-4 text-brand-600 font-bold">{user.trust_score}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {activeTab === 'audit' && (
            <div>
              <h2 className="text-xl font-semibold mb-6">Immutable Audit Log</h2>
              <div className="space-y-3">
                {audit.map((log: any) => (
                  <div key={log.audit_id} className="flex items-center justify-between p-3 bg-gray-50 rounded border border-gray-200">
                    <div className="flex gap-4">
                      <span className="bg-gray-200 text-gray-700 text-xs font-bold px-2 py-1 rounded">{log.actor_type}</span>
                      <span className="text-gray-900">{log.action_type}</span>
                      <span className="text-gray-500">- {log.reference_type} #{log.reference_id}</span>
                    </div>
                    <span className="text-xs text-gray-400">{new Date(log.action_timestamp).toLocaleString()}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {activeTab === 'config' && (
            <div>
              <h2 className="text-xl font-semibold mb-6">Tenant Configuration</h2>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                {Object.entries(config).map(([key, val]) => (
                  <div key={key} className="flex flex-col gap-1">
                    <label className="text-sm font-medium text-gray-700 capitalize">{key.replace(/_/g, ' ')}</label>
                    <input type="text" defaultValue={val as string} className="border border-gray-300 rounded px-3 py-2 focus:ring-2 focus:ring-brand-500 outline-none transition-shadow" />
                  </div>
                ))}
              </div>
              <button className="mt-8 bg-brand-600 text-white font-medium px-6 py-2 rounded shadow hover:bg-brand-700 transition-colors">Save Configuration</button>
            </div>
          )}

        </div>
      </div>
    </div>
  );
}

export default App;
