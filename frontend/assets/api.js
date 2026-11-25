/** @typedef {'VIEWER'|'MODERATOR'|'ADMINISTRATOR'} Role */
/** @typedef {{username:string, role:Role, csrf:string, expires:number}} Session */
/** @typedef {{id:number,username:string,gameMaster:boolean,knights:number}} Account */
/** Typed boundary: parameters are scalar values; authentication stays in HttpOnly cookies. */
export class ApiError extends Error {
    /** @param {string} message @param {number} status */
    constructor(message, status) {
        super(message);
        this.status = status;
    }
}
export class Api {
    /** @type {Session|null} */ session = null;
    /** @template T @param {string} route @param {Record<string,string|number|boolean>} [values] @param {boolean} [write] @returns {Promise<T>} */
    async request(route, values = {}, write = false, signal) {
        const first = await this.send(route, values, write, signal);
        const data = await first.json();
        if (first.ok) return data;
        // A rotated CSRF token would otherwise turn every write into a dead end:
        // refresh the session once and replay the request that was refused.
        if (first.status === 403 && write && this.session) {
            try {
                await this.restore();
            } catch {
                /* the replay reports the truth below either way */
            }
            const retry = await this.send(route, values, write, signal);
            const retried = await retry.json();
            if (retry.ok) return retried;
            throw new ApiError(retried.error || 'Request failed', retry.status);
        }
        throw new ApiError(data.error || 'Request failed', first.status);
    }
    /** One fetch, so a refused write can be replayed with a fresh token. */
    async send(route, values = {}, write = false, signal) {
        const parameters = new URLSearchParams(
            Object.entries(values).map(([k, v]) => [k, String(v)])
        );
        return fetch(
            '/admin/api/' + route + (write || !parameters.size ? '' : '?' + parameters),
            {
                method: write ? 'POST' : 'GET',
                credentials: 'same-origin',
                cache: 'no-store',
                headers: write
                    ? {
                          'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                          'X-CSRF-Token': this.session?.csrf || ''
                      }
                    : {},
                body: write ? parameters : undefined,
                signal: signal
                    ? AbortSignal.any([signal, AbortSignal.timeout(15000)])
                    : AbortSignal.timeout(15000)
            }
        );
    }
    /** @returns {Promise<Session>} */ async restore() {
        return (this.session = await this.request('session'));
    }
    /** @param {string} username @param {string} password @returns {Promise<Session>} */
    async login(username, password) {
        return (this.session = await this.request('login', { username, password }, true));
    }
    /** @param {string} username @param {string} password @param {string} passwordConfirmation @param {string} setupCode @returns {Promise<Session>} */
    async setup(username, password, passwordConfirmation, setupCode) {
        return (this.session = await this.request(
            'setup',
            { username, password, passwordConfirmation, setupCode },
            true
        ));
    }
    /** @returns {Promise<{read:string[],actions:string[],help:string}>} */
    async consoleCommands() {
        return this.request('console');
    }
    /** @param {string} command @returns {Promise<{output:string}>} */
    async consoleCommand(command) {
        return this.change('console', { command });
    }
    async logout() {
        await this.request('logout', {}, true);
        this.session = null;
    }
    /** @param {string} route @param {Record<string,string|number|boolean>} values */
    async change(route, values) {
        return this.request(route, { ...values, confirmation: route }, true);
    }
}
export const api = new Api();
