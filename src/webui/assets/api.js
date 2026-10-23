/** @typedef {'VIEWER'|'MODERATOR'|'ADMINISTRATOR'} Role */
/** @typedef {{username:string, role:Role, csrf:string, expires:number}} Session */
/** @typedef {{id:number,username:string,gameMaster:boolean,knights:number}} Account */
/** Typed boundary: parameters are scalar values; authentication stays in HttpOnly cookies. */
export class ApiError extends Error {
    /** @param {string} message @param {number} status */
    constructor(message, status) { super(message); this.status = status; }
}
export class Api {
    /** @type {Session|null} */ session = null;
    /** @template T @param {string} route @param {Record<string,string|number|boolean>} [values] @param {boolean} [write] @returns {Promise<T>} */
    async request(route, values = {}, write = false) {
        const parameters = new URLSearchParams(Object.entries(values).map(([k, v]) => [k, String(v)]));
        const response = await fetch('/admin/api/' + route + (write || !parameters.size ? '' : '?' + parameters), {
            method: write ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store',
            headers: write ? { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', 'X-CSRF-Token': this.session?.csrf || '' } : {},
            body: write ? parameters : undefined, signal: AbortSignal.timeout(15000)
        });
        const data = await response.json();
        if (!response.ok) {
            throw new ApiError(data.error || 'Request failed', response.status);
        }
        return data;
    }
    /** @returns {Promise<Session>} */ async restore() { return this.session = await this.request('session'); }
    /** @param {string} username @param {string} password @returns {Promise<Session>} */
    async login(username, password) { return this.session = await this.request('login', { username, password }, true); }
    /** @param {string} username @param {string} password @param {string} passwordConfirmation @param {string} setupCode @returns {Promise<Session>} */
    async setup(username,password,passwordConfirmation,setupCode) {
        return this.session = await this.request('setup',{username,password,passwordConfirmation,setupCode},true);
    }
    /** @returns {Promise<{read:string[],actions:string[],help:string}>} */
    async consoleCommands() { return this.request('console'); }
    /** @param {string} command @returns {Promise<{output:string}>} */
    async consoleCommand(command) { return this.change('console',{command}); }
    async logout() { await this.request('logout', {}, true); this.session = null; }
    /** @param {string} route @param {Record<string,string|number|boolean>} values */
    async change(route, values) { return this.request(route, { ...values, confirmation: route }, true); }
}
