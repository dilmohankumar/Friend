import { Injectable } from '@nestjs/common';
import type { Server } from 'socket.io';

/**
 * Thin wrapper around the Socket.IO server so HTTP controllers can push real-time events.
 * With the Redis adapter, room emits and fetchSockets() work across all API instances.
 */
@Injectable()
export class RealtimeBus {
  private server?: Server;

  attach(server: Server) { this.server = server; }

  static room(userId: string) { return `user:${userId}`; }

  emitToUser(userId: string, event: string, data: unknown) {
    this.server?.to(RealtimeBus.room(userId)).emit(event, data);
  }

  async isOnline(userId: string): Promise<boolean> {
    if (!this.server) return false;
    const sockets = await this.server.in(RealtimeBus.room(userId)).fetchSockets();
    return sockets.length > 0;
  }

  disconnectUser(userId: string) {
    this.server?.in(RealtimeBus.room(userId)).disconnectSockets(true);
  }
}
