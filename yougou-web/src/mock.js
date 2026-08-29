// 后端未完成时的演示数据；API 可用后组件会自动改用真实数据。
export const mockProducts = [
  { id: 1, title: '轻盈通勤双肩包', priceCent: 15900, category: '箱包', merchantName: '森屿生活馆', image: 'https://images.unsplash.com/photo-1553062407-98eeb64c6a62?auto=format&fit=crop&w=800&q=80', description: '简约耐用，适合通勤和短途出行。' },
  { id: 2, title: '手冲咖啡入门套装', priceCent: 23900, category: '家居', merchantName: '慢萃咖啡', image: 'https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?auto=format&fit=crop&w=800&q=80', description: '滤杯、分享壶与量勺，一套开启手冲生活。' },
  { id: 3, title: '降噪蓝牙耳机', priceCent: 32900, category: '数码', merchantName: '极光数码', image: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=800&q=80', description: '舒适佩戴，专注你的每一段声音。' },
  { id: 4, title: '香氛蜡烛礼盒', priceCent: 9900, category: '生活', merchantName: '森屿生活馆', image: 'https://images.unsplash.com/photo-1602874801006-e26c8ec1d232?auto=format&fit=crop&w=800&q=80', description: '木质调香氛，为日常留一段安静时光。' }
]

export const money = (cent = 0) => `¥${(cent / 100).toFixed(2)}`
