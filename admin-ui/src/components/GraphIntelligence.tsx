import { useState, useEffect } from 'react';
import { Network, ArrowRight, ShieldAlert, X } from 'lucide-react';

interface Node {
  id: string;
  name: string;
  cluster: string;
  type: 'content' | 'coordination';
  trust: number;
  x: number;
  y: number;
}

interface Edge {
  source: string;
  target: string;
  type: 'content' | 'coordination';
  weight: number;
}

const DEFAULT_NODES: Node[] = [
  // Content cluster (Spam Ring: shared duplicate hashes)
  { id: '1', name: 'botnet_01', cluster: 'Spam Ring #1', type: 'content', trust: 12.0, x: 90, y: 65 },
  { id: '2', name: 'botnet_02', cluster: 'Spam Ring #1', type: 'content', trust: 14.5, x: 175, y: 55 },
  { id: '3', name: 'crypto_bot', cluster: 'Spam Ring #1', type: 'content', trust: 9.8, x: 135, y: 135 },
  { id: '4', name: 'relay_alt', cluster: 'Spam Ring #1', type: 'content', trust: 18.0, x: 65, y: 140 },

  // Coordination cluster (Brigading Swarm: coordinated reporting against target)
  { id: '5', name: 'troll_01', cluster: 'Brigade Swarm #2', type: 'coordination', trust: 24.0, x: 340, y: 55 },
  { id: '6', name: 'troll_02', cluster: 'Brigade Swarm #2', type: 'coordination', trust: 28.5, x: 425, y: 70 },
  { id: '7', name: 'brigade_lead', cluster: 'Brigade Swarm #2', type: 'coordination', trust: 21.0, x: 375, y: 135 },
  { id: '8', name: 'target_user', cluster: 'Targeted Account', type: 'coordination', trust: 96.0, x: 455, y: 145 },
];

const DEFAULT_EDGES: Edge[] = [
  { source: '1', target: '2', type: 'content', weight: 5 },
  { source: '2', target: '3', type: 'content', weight: 4 },
  { source: '3', target: '4', type: 'content', weight: 3 },
  { source: '4', target: '1', type: 'content', weight: 4 },
  { source: '1', target: '3', type: 'content', weight: 6 },

  { source: '5', target: '6', type: 'coordination', weight: 5 },
  { source: '6', target: '7', type: 'coordination', weight: 6 },
  { source: '5', target: '7', type: 'coordination', weight: 4 },
  { source: '7', target: '8', type: 'coordination', weight: 8 },
  { source: '5', target: '8', type: 'coordination', weight: 5 },
];

interface Props {
  variant?: 'compact' | 'full';
  onExpand?: () => void;
}

export function GraphIntelligence({ variant = 'full', onExpand }: Props) {
  const [nodes, setNodes] = useState<Node[]>(DEFAULT_NODES);
  const [edges] = useState<Edge[]>(DEFAULT_EDGES);
  const [selectedNode, setSelectedNode] = useState<Node | null>(null);

  useEffect(() => {
    fetch('http://localhost:8080/api/v1/admin/clusters')
      .then(r => r.json())
      .then(data => {
        if (data.nodes?.length) {
          setNodes(prev => data.nodes.map((n: any, i: number) => ({
            ...n,
            x: prev[i]?.x ?? (100 + (i * 45) % 360),
            y: prev[i]?.y ?? (60 + Math.floor(i / 8) * 80),
            trust: n.trustScore ?? 20.0,
            cluster: n.clusterName || n.clusterId,
            type: n.clusterType === 'CONTENT' ? 'content' : 'coordination'
          })));
        }
      })
      .catch(() => {});
  }, []);

  const handlePenalize = async (nodeId: string) => {
    try {
      await fetch('http://localhost:8080/api/v1/admin/clusters/penalize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userIds: [nodeId], penalty: 50.0 })
      });
    } catch {}
    setNodes(prev => prev.map(n => n.id === nodeId ? { ...n, trust: 0 } : n));
    setSelectedNode(prev => prev && prev.id === nodeId ? { ...prev, trust: 0 } : prev);
  };

  const nodeMap = new Map(nodes.map(n => [n.id, n]));
  const isCompact = variant === 'compact';

  return (
    <div className="bg-white dark:bg-zinc-900 border border-gray-200 dark:border-zinc-800 p-4 shadow-sm">
      {/* Header */}
      <div className="flex justify-between items-center mb-3">
        <div className="flex items-center gap-2">
          <Network className="w-4 h-4 text-orange-500" />
          <h3 className="text-sm font-semibold text-gray-900 dark:text-zinc-100">Graph Intelligence</h3>
          <span className="text-[11px] font-mono bg-red-100 dark:bg-red-500/20 text-red-600 dark:text-red-400 px-1.5 py-0.5 border border-red-200 dark:border-red-500/30">
            2 Clusters Active
          </span>
        </div>
        {isCompact && onExpand && (
          <button
            onClick={onExpand}
            className="flex items-center gap-1 text-xs text-gray-500 hover:text-orange-500 transition-colors"
          >
            <span>Explore</span>
            <ArrowRight className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {/* Graph SVG Canvas */}
      <div className="border border-gray-100 dark:border-zinc-800/80 bg-gray-50 dark:bg-zinc-950 p-2 overflow-hidden">
        <svg viewBox="0 0 520 190" className="w-full h-48 select-none">
          {/* Edges */}
          {edges.map((e, idx) => {
            const s = nodeMap.get(e.source);
            const t = nodeMap.get(e.target);
            if (!s || !t) return null;
            const isContent = e.type === 'content';
            return (
              <line
                key={idx}
                x1={s.x}
                y1={s.y}
                x2={t.x}
                y2={t.y}
                stroke={isContent ? '#EF4444' : '#A855F7'}
                strokeWidth={e.weight > 5 ? 2.5 : 1.5}
                strokeDasharray={isContent ? 'none' : '3,3'}
                strokeOpacity={0.6}
              />
            );
          })}

          {/* Nodes */}
          {nodes.map(n => {
            const isSelected = selectedNode?.id === n.id;
            const isContent = n.type === 'content';
            const color = n.trust > 80 ? '#0284C7' : isContent ? '#DC2626' : '#7C3AED';

            return (
              <g
                key={n.id}
                onClick={() => setSelectedNode(n)}
                className="cursor-pointer transition-transform hover:opacity-80"
              >
                <circle
                  cx={n.x}
                  cy={n.y}
                  r={12}
                  fill={color}
                  stroke={isSelected ? '#FFFFFF' : '#404040'}
                  strokeWidth={isSelected ? 2.5 : 1}
                />
                <text
                  x={n.x}
                  y={n.y + 3.5}
                  textAnchor="middle"
                  className="text-[9px] font-mono fill-white pointer-events-none select-none font-bold"
                >
                  {n.trust > 80 ? 'VICTIM' : isContent ? 'BOT' : 'SWARM'}
                </text>
                <text
                  x={n.x}
                  y={n.y + 22}
                  textAnchor="middle"
                  className={`text-[10px] select-none ${isSelected ? 'fill-orange-500 font-bold' : 'fill-gray-700 dark:fill-zinc-300'}`}
                >
                  {n.name}
                </text>
              </g>
            );
          })}
        </svg>

        {/* Legend */}
        <div className="flex justify-between items-center text-[11px] text-gray-500 dark:text-zinc-400 px-2 pt-2 border-t border-gray-200/50 dark:border-zinc-800">
          <div className="flex items-center gap-3">
            <span className="flex items-center gap-1">
              <span className="w-2 h-2 rounded-full bg-red-500 inline-block" /> Spam Ring (Hashes)
            </span>
            <span className="flex items-center gap-1">
              <span className="w-2 h-2 rounded-full bg-purple-500 inline-block" /> Brigade Swarm (Reports)
            </span>
            <span className="flex items-center gap-1">
              <span className="w-2 h-2 rounded-full bg-sky-500 inline-block" /> Target
            </span>
          </div>
          <span className="font-mono text-[10px]">{nodes.length} Nodes • {edges.length} Edges</span>
        </div>
      </div>

      {/* Selected Node Details Bar */}
      {selectedNode && (
        <div className="mt-3 p-2.5 bg-gray-50 dark:bg-zinc-950 border border-gray-200 dark:border-zinc-800 flex flex-wrap justify-between items-center gap-2 text-xs">
          <div>
            <span className="font-bold text-gray-900 dark:text-zinc-100">{selectedNode.name}</span>
            <span className="text-gray-400 dark:text-zinc-500 ml-2">({selectedNode.cluster})</span>
            <span className="ml-3 font-mono text-[11px] text-gray-500">
              Trust: <span className={selectedNode.trust < 20 ? 'text-red-500 font-bold' : 'text-green-500'}>{selectedNode.trust.toFixed(1)}</span>
            </span>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => handlePenalize(selectedNode.id)}
              className="px-2.5 py-1 bg-red-600 hover:bg-red-700 text-white text-xs font-medium flex items-center gap-1 transition-colors"
            >
              <ShieldAlert className="w-3 h-3" /> Penalize
            </button>
            <button
              onClick={() => setSelectedNode(null)}
              className="text-gray-400 hover:text-gray-600 dark:hover:text-zinc-200 p-1"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export const Graph = GraphIntelligence;
export default GraphIntelligence;
