import { Client, IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type { GameState } from '@/types/api'

type GameStateCallback = (state: GameState) => void
type GenericCallback = (data: unknown) => void

class WebSocketClient {
  private client: Client | null = null
  private gameStateCallbacks: Set<GameStateCallback> = new Set()
  private championSelectCallbacks: Set<GenericCallback> = new Set()
  private lobbyCallbacks: Set<GenericCallback> = new Set()

  /**
   * 连接到后端 WebSocket
   */
  connect(): void {
    if (this.client?.connected) {
      return
    }

    this.client = new Client({
      webSocketFactory: () => new SockJS('http://127.0.0.1:8080/ws'),
      reconnectDelay: 3000,
      onConnect: () => {
        console.log('WebSocket connected')
        this.subscribeToTopics()
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected')
      },
      onStompError: (frame) => {
        console.error('STOMP error:', frame.headers['message'])
      },
      onWebSocketError: (event) => {
        console.error('WebSocket error:', event)
      }
    })

    this.client.activate()
  }

  /**
   * 订阅主题
   */
  private subscribeToTopics(): void {
    if (!this.client) return

    // 游戏状态变化
    this.client.subscribe('/topic/game-state', (message: IMessage) => {
      const data = JSON.parse(message.body) as GameState
      this.gameStateCallbacks.forEach(cb => cb(data))
    })

    // 选人阶段变化
    this.client.subscribe('/topic/champion-select', (message: IMessage) => {
      const data = JSON.parse(message.body)
      this.championSelectCallbacks.forEach(cb => cb(data))
    })

    // 大厅变化
    this.client.subscribe('/topic/lobby', (message: IMessage) => {
      const data = JSON.parse(message.body)
      this.lobbyCallbacks.forEach(cb => cb(data))
    })
  }

  /**
   * 断开连接
   */
  disconnect(): void {
    if (this.client) {
      this.client.deactivate()
      this.client = null
    }
  }

  /**
   * 是否已连接
   */
  isConnected(): boolean {
    return this.client?.connected ?? false
  }

  // ========== 事件订阅 ==========

  /**
   * 订阅游戏状态变化
   */
  onGameState(callback: GameStateCallback): () => void {
    this.gameStateCallbacks.add(callback)
    return () => this.gameStateCallbacks.delete(callback)
  }

  /**
   * 订阅选人阶段变化
   */
  onChampionSelect(callback: GenericCallback): () => void {
    this.championSelectCallbacks.add(callback)
    return () => this.championSelectCallbacks.delete(callback)
  }

  /**
   * 订阅大厅变化
   */
  onLobby(callback: GenericCallback): () => void {
    this.lobbyCallbacks.add(callback)
    return () => this.lobbyCallbacks.delete(callback)
  }
}

export const wsClient = new WebSocketClient()
