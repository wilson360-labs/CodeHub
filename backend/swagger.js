/**
 * CodeHub API — Swagger/OpenAPI 3.0 Spec
 * Accesible en: /api/docs
 */

const swaggerSpec = {
  openapi: '3.0.0',
  info: {
    title: 'CodeHub API',
    version: '3.0.0',
    description: 'API REST de CodeHub — Wilson.E 2026\n\nBase URL: `https://codehub-98s6.onrender.com`',
    contact: {
      name: 'Wilson.E',
      email: 'wilson.e360labs@gmail.com',
      url: 'https://wilson360-labs.vercel.app',
    },
    license: { name: 'MIT' },
  },
  servers: [
    { url: 'https://codehub-98s6.onrender.com', description: 'Producción (Render)' },
    { url: 'http://localhost:3001', description: 'Desarrollo local' },
  ],
  tags: [
    { name: 'Seguridad',  description: 'Analisis de links y reputacion con VirusTotal' },
    { name: 'Sistema',    description: 'Health check y estado del servidor' },
    { name: 'Apps',       description: 'Tienda de apps Android' },
    { name: 'Ratings',    description: 'Sistema de calificaciones' },
    { name: 'Requests',   description: 'Solicitudes de la comunidad' },
    { name: 'Chat',       description: 'Chat IA (Groq/Gemini)' },
    { name: 'Imágenes',   description: 'Generador de imágenes IA' },
    { name: 'Admin',      description: 'Panel de administración (requiere ADMIN_KEY)' },
  ],
  components: {
    securitySchemes: {
      AdminKey: {
        type: 'apiKey',
        in: 'header',
        name: 'x-admin-key',
        description: 'Clave de administración del backend',
      },
    },
    schemas: {
      App: {
        type: 'object',
        properties: {
          appId:        { type: 'string', example: 'spotify' },
          nombre:       { type: 'string', example: 'Spotify Premium' },
          descripcion:  { type: 'string' },
          version:      { type: 'string', example: '8.9.12' },
          tag:          { type: 'string', example: '🆕' },
          changelog:    { type: 'string' },
          imagen:       { type: 'string', format: 'uri' },
          categoria:    { type: 'string', example: 'Música' },
          verified:     { type: 'boolean' },
          enlace:       { type: 'string', format: 'uri' },
          plugin_enlace:{ type: 'string', format: 'uri', nullable: true },
          updatedAt:    { type: 'string', format: 'date-time' },
        },
      },
      Health: {
        type: 'object',
        properties: {
          status:  { type: 'string', example: 'ok' },
          version: { type: 'string', example: '3.0' },
          mongo:   { type: 'string', example: 'connected' },
          redis:   { type: 'string', example: 'memory' },
          ws:      { type: 'string', example: '2 clients' },
          uptime:  { type: 'string', example: '3600s' },
        },
      },
      Error: {
        type: 'object',
        properties: {
          error: { type: 'string' },
        },
      },
    },
  },
  paths: {
    '/api/health': {
      get: {
        tags: ['Sistema'],
        summary: 'Health check del backend',
        responses: {
          200: {
            description: 'Backend operativo',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Health' } } },
          },
        },
      },
    },
    '/api/apps': {
      get: {
        tags: ['Apps'],
        summary: 'Listar todas las apps (caché 5 min)',
        responses: {
          200: {
            description: 'Lista de apps',
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: {
                    apps:  { type: 'array', items: { $ref: '#/components/schemas/App' } },
                    total: { type: 'integer' },
                  },
                },
              },
            },
          },
        },
      },
    },
    '/api/ratings': {
      get: {
        tags: ['Ratings'],
        summary: 'Obtener ratings de todas las apps',
        responses: {
          200: {
            description: 'Ratings',
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: {
                    ratings: {
                      type: 'object',
                      additionalProperties: {
                        type: 'object',
                        properties: {
                          avg:   { type: 'number', example: 4.5 },
                          count: { type: 'integer', example: 12 },
                        },
                      },
                    },
                  },
                },
              },
            },
          },
        },
      },
      post: {
        tags: ['Ratings'],
        summary: 'Votar una app (1 voto por IP)',
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: {
                type: 'object',
                required: ['appId', 'stars'],
                properties: {
                  appId:   { type: 'string', example: 'spotify' },
                  appName: { type: 'string', example: 'Spotify' },
                  stars:   { type: 'integer', minimum: 1, maximum: 5, example: 5 },
                },
              },
            },
          },
        },
        responses: {
          200: { description: 'Voto guardado' },
          400: { description: 'Datos inválidos', content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } } },
          409: { description: 'Ya votaste' },
        },
      },
    },
    '/api/chat': {
      post: {
        tags: ['Chat'],
        summary: 'Enviar mensaje al chat IA',
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: {
                type: 'object',
                required: ['message'],
                properties: {
                  message:   { type: 'string', maxLength: 1000, example: 'Hola, ¿qué herramientas tiene CodeHub?' },
                  sessionId: { type: 'string', example: 'user-abc123' },
                  history:   { type: 'array', items: { type: 'object' } },
                },
              },
            },
          },
        },
        responses: {
          200: {
            description: 'Respuesta del asistente',
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: {
                    reply: { type: 'string' },
                    model: { type: 'string', example: 'groq/llama-3.3-70b' },
                    usage: { type: 'object' },
                  },
                },
              },
            },
          },
          429: { description: 'Rate limit alcanzado' },
        },
      },
    },
    '/api/generate-image': {
      post: {
        tags: ['Imágenes'],
        summary: 'Generar imagen con IA (Together → Gemini → Pollinations)',
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: {
                type: 'object',
                required: ['prompt'],
                properties: {
                  prompt:   { type: 'string', example: 'a dragon flying over Guatemala mountains' },
                  width:    { type: 'integer', default: 512, minimum: 256, maximum: 1024 },
                  height:   { type: 'integer', default: 512, minimum: 256, maximum: 1024 },
                  provider: { type: 'string', enum: ['auto', 'together', 'gemini', 'minimax', 'pollinations'], default: 'auto' },
                },
              },
            },
          },
        },
        responses: {
          200: {
            description: 'Imagen generada',
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: {
                    ok:       { type: 'boolean' },
                    provider: { type: 'string' },
                    model:    { type: 'string' },
                    image:    { type: 'string', description: 'Base64 data URL' },
                  },
                },
              },
            },
          },
          503: { description: 'Todos los proveedores fallaron' },
        },
      },
    },
    '/api/check-link': {
      post: {
        tags: ['Seguridad'],
        summary: 'Analizar un link con VirusTotal',
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: {
                type: 'object',
                required: ['url'],
                properties: {
                  url: { type: 'string', example: 'https://example.com/download' },
                },
              },
            },
          },
        },
        responses: {
          200: {
            description: 'Analisis del link',
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: {
                    ok: { type: 'boolean' },
                    provider: { type: 'string', example: 'virustotal' },
                    url: { type: 'string' },
                    host: { type: 'string' },
                    verdict: { type: 'string', enum: ['clean', 'suspicious', 'malicious', 'unknown'] },
                    riskScore: { type: 'integer', example: 25 },
                    recommendation: { type: 'string' },
                  },
                },
              },
            },
          },
          400: { description: 'URL invalida' },
          503: { description: 'VirusTotal no configurado' },
        },
      },
    },
    '/api/check-file': {
      post: {
        tags: ['Seguridad'],
        summary: 'Analizar un archivo con VirusTotal',
        requestBody: {
          required: true,
          content: {
            'multipart/form-data': {
              schema: {
                type: 'object',
                required: ['file'],
                properties: {
                  file: { type: 'string', format: 'binary' },
                },
              },
            },
          },
        },
        responses: {
          200: {
            description: 'Analisis del archivo',
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: {
                    ok: { type: 'boolean' },
                    provider: { type: 'string', example: 'virustotal' },
                    fileName: { type: 'string' },
                    mime: { type: 'string' },
                    size: { type: 'integer' },
                    sha256: { type: 'string' },
                    verdict: { type: 'string', enum: ['clean', 'suspicious', 'malicious'] },
                    riskScore: { type: 'integer', example: 25 },
                    recommendation: { type: 'string' },
                  },
                },
              },
            },
          },
          400: { description: 'Archivo invalido o demasiado grande' },
          503: { description: 'VirusTotal no configurado' },
        },
      },
    },
    '/api/requests': {
      get: {
        tags: ['Requests'],
        summary: 'Ver solicitudes de apps pendientes',
        responses: { 200: { description: 'Lista de solicitudes' } },
      },
      post: {
        tags: ['Requests'],
        summary: 'Solicitar una app o votar una existente',
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: {
                type: 'object',
                required: ['appName'],
                properties: {
                  appName:        { type: 'string', example: 'Adobe Lightroom' },
                  reason:         { type: 'string' },
                  turnstileToken: { type: 'string' },
                },
              },
            },
          },
        },
        responses: {
          200: { description: 'Solicitud enviada o voto agregado' },
          409: { description: 'Ya votaste esta solicitud' },
        },
      },
    },
    '/api/download/{fileName}': {
      get: {
        tags: ['Apps'],
        summary: 'Descargar APK (URL firmada de Backblaze B2)',
        parameters: [{
          name: 'fileName',
          in: 'path',
          required: true,
          schema: { type: 'string', example: 'spotify_main_1234567890.apk' },
        }],
        responses: {
          302: { description: 'Redirect a URL firmada de B2' },
          400: { description: 'Nombre de archivo inválido' },
          500: { description: 'Error generando URL firmada' },
        },
      },
    },
    '/api/admin/apps': {
      get: {
        tags: ['Admin'],
        summary: 'Listar apps (sin caché)',
        security: [{ AdminKey: [] }],
        responses: { 200: { description: 'Lista completa' }, 403: { description: 'No autorizado' } },
      },
      post: {
        tags: ['Admin'],
        summary: 'Crear nueva app',
        security: [{ AdminKey: [] }],
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: {
                type: 'object',
                required: ['appId', 'nombre'],
                properties: {
                  appId:    { type: 'string', example: 'nueva-app' },
                  nombre:   { type: 'string', example: 'Nueva App' },
                  version:  { type: 'string' },
                  tag:      { type: 'string', default: '🆕' },
                  categoria:{ type: 'string' },
                  verified: { type: 'boolean', default: true },
                },
              },
            },
          },
        },
        responses: { 200: { description: 'App creada' }, 409: { description: 'Ya existe' } },
      },
    },
    '/api/admin/apps/{appId}': {
      patch: {
        tags: ['Admin'],
        summary: 'Actualizar app',
        security: [{ AdminKey: [] }],
        parameters: [{ name: 'appId', in: 'path', required: true, schema: { type: 'string' } }],
        requestBody: {
          content: {
            'application/json': {
              schema: {
                type: 'object',
                properties: {
                  version: { type: 'string' },
                  tag:     { type: 'string' },
                  changelog: { type: 'string' },
                  verified:  { type: 'boolean' },
                },
              },
            },
          },
        },
        responses: { 200: { description: 'App actualizada' }, 404: { description: 'No encontrada' } },
      },
      delete: {
        tags: ['Admin'],
        summary: 'Eliminar app y su APK de B2',
        security: [{ AdminKey: [] }],
        parameters: [{ name: 'appId', in: 'path', required: true, schema: { type: 'string' } }],
        responses: { 200: { description: 'App eliminada' }, 404: { description: 'No encontrada' } },
      },
    },
  },
};

module.exports = swaggerSpec;
