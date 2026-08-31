export interface PreparationToken {
  readonly generation: number;
  readonly signature: string;
}

/** Keeps async preparation results from crossing a settings change boundary. */
export class LatestPreparation<T> {
  private generation = 0;
  private signature = '';
  private value: T | null = null;

  invalidate(): void {
    this.generation += 1;
    this.signature = '';
    this.value = null;
  }

  token(signature: string): PreparationToken {
    return { generation: this.generation, signature };
  }

  cached(token: PreparationToken): T | null {
    return this.isCurrent(token) && token.signature === this.signature ? this.value : null;
  }

  commit(token: PreparationToken, value: T): boolean {
    if (!this.isCurrent(token)) return false;
    this.signature = token.signature;
    this.value = value;
    return true;
  }

  isCurrent(token: PreparationToken): boolean {
    return token.generation === this.generation;
  }
}
