# Compila dentro de la imagen: el build/ de producción ni se toca.
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend_sigep/package.json frontend_sigep/package-lock.json ./
RUN npm ci
COPY frontend_sigep/ .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/build /usr/share/nginx/html
COPY pruebas/nginx.conf /etc/nginx/conf.d/default.conf
