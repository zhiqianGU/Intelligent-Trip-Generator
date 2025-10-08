(function () {
    const gen = () =>
        (crypto.randomUUID ? crypto.randomUUID()
            : Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join(''));

    axios.interceptors.request.use(cfg => {
        const m = (cfg.method || 'get').toUpperCase();
        if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(m)) {
            cfg.headers = cfg.headers || {};
            cfg.headers['Idempotency-Key'] = cfg.headers['Idempotency-Key'] || gen();
        }
        return cfg;
    });
})();